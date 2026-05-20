package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.INFO
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
 * WebSocket client for `/v1/ws`. Owns one supervisory coroutine per active connector, all
 * bound to [AppGraph.appScope].
 *
 * State machine per connector:
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
 * - **Polling fallback**: after [MAX_BACKOFF_ATTEMPTS] consecutive failures on a connector,
 *   we stop trying for that connector. [DeviceListRepo]'s periodic poll is the recovery path;
 *   [retry] allows a manual WS retry.
 * - **Self-suppression**: events whose `sourceDeviceId == ownDeviceId` are dropped before
 *   reaching the bus (Codex review #5 wire detail).
 *
 * The whole multi-connector loop set is restarted when [AppGraph.activeConnectors]
 * transitions — set deltas drive create/cancel. Today the set is always 0-or-1; the code is
 * written for n so a future GDrive connector slots in cleanly.
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

    private val _statesByConnector = MutableStateFlow<Map<ConnectorId, ConnectionState>>(emptyMap())

    /** Per-connector connection state. Empty when no connectors are active. */
    val statesByConnector: StateFlow<Map<ConnectorId, ConnectionState>> = _statesByConnector.asStateFlow()

    /**
     * Aggregate state view for back-compat with the dashboard/debug single-connector callers.
     * Today the value is always `statesByConnector[primaryConnector] ?: Idle`; when a second
     * connector lands, callers that care about per-connector detail switch to
     * [statesByConnector] — the dashboard polling-fallback gate will need a rule like "any
     * connector in PollingFallback" rather than "the only one is".
     */
    val state: StateFlow<ConnectionState> = graph.primaryConnector
        .map { primary ->
            primary?.let { _statesByConnector.value[it.identifier] } ?: ConnectionState.Idle
        }
        .stateIn(graph.appScope, SharingStarted.Eagerly, ConnectionState.Idle)

    private val retrySignals = MutableStateFlow<Map<ConnectorId, Int>>(emptyMap())
    private val lifecycleLock = Mutex()
    private var lifecycleJobs: MutableMap<ConnectorId, Job> = mutableMapOf()

    fun start() {
        graph.activeConnectors
            .onEach { connectors ->
                lifecycleLock.withLock {
                    val active = connectors.associateBy { it.identifier }
                    // Cancel loops for connectors that left the active set.
                    val removed = lifecycleJobs.keys - active.keys
                    for (id in removed) {
                        lifecycleJobs.remove(id)?.cancel()
                        _statesByConnector.update { it - id }
                    }
                    // Start loops for newly active connectors.
                    for ((id, connector) in active) {
                        if (id in lifecycleJobs) continue
                        lifecycleJobs[id] = graph.appScope.launch { runLoop(connector) }
                    }
                }
            }
            .launchIn(graph.appScope)
    }

    /**
     * Manual nudge out of [ConnectionState.PollingFallback] to retry the WS connect. If
     * [connectorId] is null, retries every connector currently in PollingFallback. Today the
     * UI only ever has one connector to nudge, but the parameterized form keeps the call site
     * shape stable when GDrive lands.
     */
    fun retry(connectorId: ConnectorId? = null) {
        retrySignals.update { map ->
            if (connectorId == null) {
                // Bump every connector's signal — the runLoop only awaits its own counter.
                map + _statesByConnector.value.keys.associateWith { (map[it] ?: 0) + 1 }
            } else {
                map + (connectorId to (map[connectorId] ?: 0) + 1)
            }
        }
    }

    private suspend fun runLoop(connector: OctiServerConnector) {
        val id = connector.identifier
        val client = connector.client
        var consecutiveFailures = 0
        while (true) {
            _statesByConnector.update { it + (id to ConnectionState.Connecting) }
            log(TAG, INFO) { "[${id.logLabel}] Opening WebSocket session (attempt=${consecutiveFailures + 1})" }
            val sessionOk = try {
                val session = client.openWebSocketSession()
                consecutiveFailures = 0
                _statesByConnector.update { it + (id to ConnectionState.Connected) }
                consume(session)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "[${id.logLabel}] WebSocket session ended unexpectedly" }
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
                _statesByConnector.update { it + (id to ConnectionState.PollingFallback) }
                log(TAG, WARN) {
                    "[${id.logLabel}] WebSocket reconnect failed $MAX_BACKOFF_ATTEMPTS times in a row — falling back to REST polling"
                }
                awaitRetrySignal(id)
                consecutiveFailures = 0
                continue
            }
            val nextDelay = backoffDelay(consecutiveFailures - 1)
            _statesByConnector.update { it + (id to ConnectionState.Reconnecting(consecutiveFailures, nextDelay)) }
            log(TAG, DEBUG) { "[${id.logLabel}] Backing off ${nextDelay.inWholeMilliseconds}ms before next WebSocket attempt" }
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

    private suspend fun awaitRetrySignal(id: ConnectorId) {
        val before = retrySignals.value[id] ?: 0
        // Suspend until retry() bumps THIS connector's counter.
        retrySignals.first { (it[id] ?: 0) != before }
    }

    private fun <K, V> MutableStateFlow<Map<K, V>>.update(transform: (Map<K, V>) -> Map<K, V>) {
        value = transform(value)
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
