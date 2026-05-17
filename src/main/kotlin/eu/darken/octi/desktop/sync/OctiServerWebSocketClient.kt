package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.INFO
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.serialization.Serialization
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val TAG = logTag("Sync", "WebSocket")

/**
 * WebSocket client for `/v1/ws`. Owns a single supervisory coroutine bound to [AppGraph.appScope].
 *
 * State machine:
 *
 * ```
 *      Idle ──start()──▶ Connecting ──open ok──▶ Connected ──frame──▶ (emit to bus)
 *                            ▲                       │
 *                            │                       ▼ (failure)
 *                       Reconnecting  ◀──── after backoff
 *                            │
 *                       (3 consecutive fails)
 *                            ▼
 *                     PollingFallback ──retry()──▶ Connecting
 * ```
 *
 * - **Backoff**: jittered exponential `min(60s, 2^attempt * 1s) + ±25% jitter`.
 * - **Polling fallback**: after [MAX_BACKOFF_ATTEMPTS] consecutive failures, we stop trying.
 *   `DeviceListRepo`'s periodic poll is the recovery path; [retry] allows manual WS retry.
 * - **Self-suppression**: events whose `sourceDeviceId == ownDeviceId` are dropped before
 *   reaching the bus (Codex review #5 wire detail).
 *
 * The whole loop is restarted when [AppGraph.activeClient] transitions — flatMapLatest tears
 * down the prior loop cleanly so we never run two WS sessions for two clients in parallel.
 */
class OctiServerWebSocketClient(
    private val graph: AppGraph,
    private val eventBus: SyncEventBus,
) {

    sealed class ConnectionState {
        data object Idle : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Reconnecting(val attempt: Int, val nextDelay: Duration) : ConnectionState()
        data object PollingFallback : ConnectionState()
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val retrySignal = MutableStateFlow(0)
    private val lifecycleLock = Mutex()
    private var lifecycleJob: Job? = null

    fun start() {
        graph.activeClient
            .onEach { client ->
                lifecycleLock.withLock {
                    lifecycleJob?.cancel()
                    lifecycleJob = if (client != null) {
                        graph.appScope.launch { runLoop(client) }
                    } else {
                        _state.value = ConnectionState.Idle
                        null
                    }
                }
            }
            .launchIn(graph.appScope)
    }

    /** Manual nudge out of [ConnectionState.PollingFallback] to retry the WS connect. */
    fun retry() {
        retrySignal.value++
    }

    private suspend fun runLoop(client: OctiServerHttpClient) {
        var consecutiveFailures = 0
        while (true) {
            _state.value = ConnectionState.Connecting
            log(TAG, INFO) { "Opening WebSocket session (attempt=${consecutiveFailures + 1})" }
            val sessionOk = try {
                val session = client.openWebSocketSession()
                consecutiveFailures = 0
                _state.value = ConnectionState.Connected
                consume(session)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "WebSocket session ended unexpectedly" }
                false
            }

            // sessionOk means the frame loop returned normally (server closed cleanly). Treat
            // it as a transient close and reconnect promptly with no consecutive-failure bump
            // — otherwise a clean server-side disconnect would burn through the backoff budget.
            if (sessionOk) {
                delay(1.seconds)
                continue
            }

            consecutiveFailures++
            if (consecutiveFailures >= MAX_BACKOFF_ATTEMPTS) {
                _state.value = ConnectionState.PollingFallback
                log(TAG, WARN) {
                    "WebSocket reconnect failed $MAX_BACKOFF_ATTEMPTS times in a row — falling back to REST polling"
                }
                awaitRetrySignal()
                consecutiveFailures = 0
                continue
            }
            val nextDelay = backoffDelay(consecutiveFailures - 1)
            _state.value = ConnectionState.Reconnecting(consecutiveFailures, nextDelay)
            log(TAG, DEBUG) { "Backing off ${nextDelay.inWholeMilliseconds}ms before next WebSocket attempt" }
            delay(nextDelay)
        }
    }

    private suspend fun consume(session: DefaultClientWebSocketSession) {
        try {
            session.incoming.consumeEach { frame ->
                when (frame) {
                    is Frame.Text -> handleTextFrame(frame.readText())
                    is Frame.Close -> {
                        log(TAG, INFO) {
                            "Server closed WebSocket: ${frame.readReason()?.message ?: "no reason"}"
                        }
                        return
                    }
                    else -> Unit // pings/pongs handled by Ktor automatically
                }
            }
        } finally {
            runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "client done")) }
        }
    }

    private suspend fun handleTextFrame(text: String) {
        val payload = try {
            Serialization.json.decodeFromString(EventPayload.serializer(), text)
        } catch (e: Throwable) {
            log(TAG, WARN, e) { "Failed to decode WebSocket frame; ignoring" }
            return
        }
        val ownDeviceId = graph.deviceId.id
        payload.events.forEach { event ->
            when (event) {
                is EventPayload.Event.ModuleChanged -> {
                    // Codex review #5: source-suppression is the only correctness lever the
                    // server gives us. Drop our own write echoes here, in one central place.
                    if (event.sourceDeviceId == ownDeviceId) {
                        log(TAG, DEBUG) { "Suppressing self-event for module=${event.moduleId}" }
                        return@forEach
                    }
                    log(TAG, DEBUG) {
                        "Event: module=${event.moduleId} owner=${event.deviceId.take(8)} " +
                            "action=${event.action} source=${event.sourceDeviceId.take(8)}"
                    }
                    eventBus.emit(event)
                }
            }
        }
    }

    private suspend fun awaitRetrySignal() {
        val before = retrySignal.value
        // Suspend until retry() bumps the counter.
        retrySignal.first { it != before }
    }

    companion object {
        const val MAX_BACKOFF_ATTEMPTS = 3
        private val BASE_DELAY = 1.seconds
        private val MAX_DELAY = 60.seconds

        /**
         * Jittered exponential: `min(MAX, BASE * 2^attempt) ± 25%`. Decorrelates reconnect
         * storms when many clients race to the server after an outage.
         */
        internal fun backoffDelay(attempt: Int, rng: Random = Random.Default): Duration {
            val exp = (BASE_DELAY.inWholeMilliseconds shl attempt.coerceAtMost(10))
                .coerceAtMost(MAX_DELAY.inWholeMilliseconds)
            val jitterRange = (exp / 4).toInt().coerceAtLeast(1)
            val jitter = rng.nextInt(-jitterRange, jitterRange + 1)
            return min(MAX_DELAY.inWholeMilliseconds, exp + jitter)
                .coerceAtLeast(BASE_DELAY.inWholeMilliseconds / 2)
                .milliseconds
        }
    }
}

