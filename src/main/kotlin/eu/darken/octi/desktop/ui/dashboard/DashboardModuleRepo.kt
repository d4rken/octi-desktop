package eu.darken.octi.desktop.ui.dashboard

import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpException
import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.sync.ModuleReader
import eu.darken.octi.desktop.sync.OctiServerWebSocketClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val TAG = logTag("UI", "DashboardModuleRepo")

/**
 * Result of a per-(deviceId, module) read for the Dashboard tiles. Mirrors
 * [ModuleReader.Result] but lives on the UI side so the renderer doesn't have to import the
 * sync layer.
 */
sealed class ModuleState<out T> {
    /** Before the first read completes, OR after `primaryConnector` flips to null. */
    data object Loading : ModuleState<Nothing>()

    /** Server returned 204 / NotFound — peer hasn't written this module. Render per [NotFoundPolicy]. */
    data object NotFound : ModuleState<Nothing>()

    data class Ok<T>(val value: T) : ModuleState<T>()

    data class Error(val message: String) : ModuleState<Nothing>()
}

/**
 * Per-(device, module) state store for the Dashboard tile grid. Each tile observes a slice
 * here; the repo handles reading, decryption, WS-driven invalidation, fallback polling, and
 * self-device routing in one place.
 *
 * Design (per plan + Codex review):
 *
 * - One [Slice] per `(deviceId, moduleId)`, lazily created and cached in a [ConcurrentHashMap].
 *   The slice's [StateFlow] is materialised via `stateIn(WhileSubscribed(stopTimeoutMillis = 30s))`
 *   so a tile that scrolls offscreen doesn't keep its read pipeline alive forever.
 * - **One central event collector** in [start] dispatches [EventPayload.Event.ModuleChanged] to
 *   the matching slice. Avoids one collector per slice.
 * - **`transformLatest`-driven reads**: each kick cancels the in-flight read and starts a fresh
 *   one. No manual mutexes — structured cancellation does the coalescing.
 * - **Global** [readSemaphore] caps concurrent module reads. Defaults to 6 (well under the
 *   server's 256/60s account rate limit and 512/60s IP rate limit, even during the
 *   open-dashboard burst of 4 devices × 7 modules = 28 reads).
 * - **Self-device routing**: when `(deviceId == graph.deviceId)`, certain modules source from
 *   local flows on the writers (no server round-trip), because WS suppresses self-events so the
 *   server-driven invalidation path would never fire.
 *   - meta → `MetaWriter.lastWrittenInfo`
 *   - clipboard → `ClipboardSync.lastPushedInfo`
 *   - files → `FileShareRepo.ownFiles`
 *   - other own modules → `NotFound` (desktop doesn't collect them yet).
 * - **Polling-fallback re-reads**: while `webSocketClient.state == PollingFallback`, a periodic
 *   job kicks every observed (non-self) slice every [pollingRefreshInterval]. Stops when the
 *   state leaves `PollingFallback`.
 * - **Eviction**: when a device disappears from `deviceListRepo.devices`, we drop its slices
 *   from the cache.
 * - **Active-connector transitions**: when `primaryConnector` flips, we kick all slices. The slice
 *   itself sees the null client and emits `Loading`; on relink the next kick hydrates.
 */
class DashboardModuleRepo(
    private val graph: AppGraph,
    private val scope: CoroutineScope = graph.appScope,
    private val readSemaphore: Semaphore = Semaphore(permits = 6),
    private val stopTimeoutMillis: Long = 30_000,
    private val pollingRefreshInterval: Duration = DEFAULT_POLLING_REFRESH_INTERVAL,
    private val rateLimitBackoff: Duration = DEFAULT_RATE_LIMIT_BACKOFF,
) {

    private val slices = ConcurrentHashMap<Key, Slice<*>>()
    private var managementJob: Job? = null
    private var pollingJob: Job? = null

    /** Returns the typed [StateFlow] for a (device, module) pair. Lazily creates the slice. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> flowFor(deviceId: DeviceId, spec: ModuleSpec<T>): StateFlow<ModuleState<T>> {
        val key = Key(deviceId, spec.moduleId)
        val slice = slices.computeIfAbsent(key) { buildSlice(deviceId, spec) }
        return slice.stateFlow as StateFlow<ModuleState<T>>
    }

    /** Forces a re-read of a specific slice. Used by tile retry buttons. */
    fun invalidate(deviceId: DeviceId, spec: ModuleSpec<*>) {
        slices[Key(deviceId, spec.moduleId)]?.kick()
    }

    /**
     * Wires up the central WS dispatcher, active-client transitions, device-list eviction, and
     * the polling-fallback refresh job. Idempotent — second call is a no-op.
     */
    fun start() {
        if (managementJob != null) return

        managementJob = scope.launch {
            // Central WS event dispatcher.
            launch {
                graph.syncEventBus.events
                    .onEach { syncEvent ->
                        val event = syncEvent.event
                        if (event is EventPayload.Event.ModuleChanged) {
                            slices[Key(DeviceId(event.deviceId), ModuleId(event.moduleId))]?.kick()
                        }
                    }
                    .launchIn(this)
            }

            // Active-connector transitions: kick everyone (slice itself sees the null and emits
            // Loading; on relink the next kick hydrates).
            launch {
                graph.primaryConnector
                    .onEach { slices.values.forEach { it.kick() } }
                    .launchIn(this)
            }

            // Eviction: drop slices for devices no longer in the list. Snapshot ids only so we
            // don't churn on lastSeen ticks.
            launch {
                graph.deviceListRepo.devices
                    .map { it.map { d -> d.id }.toSet() }
                    .distinctUntilChanged()
                    .onEach { ids ->
                        val toRemove = slices.keys.filter { it.deviceId.id !in ids }
                        toRemove.forEach { slices.remove(it) }
                    }
                    .launchIn(this)
            }

            // Polling-fallback periodic refresh.
            launch {
                graph.webSocketClient.state
                    .onEach { state ->
                        val isFallback = state is OctiServerWebSocketClient.ConnectionState.PollingFallback
                        if (isFallback && pollingJob == null) {
                            pollingJob = scope.launch {
                                while (true) {
                                    delay(pollingRefreshInterval.inWholeMilliseconds)
                                    slices.values.forEach { it.kick() }
                                }
                            }
                            log(TAG, DEBUG) { "WS in PollingFallback — periodic tile refresh started" }
                        } else if (!isFallback && pollingJob != null) {
                            pollingJob?.cancel()
                            pollingJob = null
                            log(TAG, DEBUG) { "WS recovered — periodic tile refresh stopped" }
                        }
                    }
                    .launchIn(this)
            }
        }
    }

    private fun <T : Any> buildSlice(deviceId: DeviceId, spec: ModuleSpec<T>): Slice<T> = Slice(
        deviceId = deviceId,
        spec = spec,
        scope = scope,
        graph = graph,
        readSemaphore = readSemaphore,
        stopTimeoutMillis = stopTimeoutMillis,
        rateLimitBackoff = rateLimitBackoff,
    )

    private data class Key(val deviceId: DeviceId, val moduleId: ModuleId)

    /**
     * One (deviceId, moduleSpec) entry. Self-device slices route to local writer flows directly;
     * remote slices materialise a hot [stateFlow] driven by a [kicks] counter.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class Slice<T : Any>(
        private val deviceId: DeviceId,
        private val spec: ModuleSpec<T>,
        private val scope: CoroutineScope,
        private val graph: AppGraph,
        private val readSemaphore: Semaphore,
        stopTimeoutMillis: Long,
        private val rateLimitBackoff: Duration,
    ) {

        private val kicks = MutableStateFlow(0L)
        private val isSelf = deviceId.id == graph.deviceId.id

        val stateFlow: StateFlow<ModuleState<T>> = if (isSelf) {
            buildSelfFlow()
        } else {
            buildRemoteFlow(stopTimeoutMillis)
        }

        fun kick() {
            if (isSelf) return // self flows are already hot from their writer source.
            kicks.value = kicks.value + 1
        }

        @Suppress("UNCHECKED_CAST")
        private fun buildSelfFlow(): StateFlow<ModuleState<T>> = when (spec) {
            ModuleSpec.Meta -> graph.metaWriter.lastWrittenInfo
                .map<MetaInfo?, ModuleState<MetaInfo>> { info ->
                    if (info != null) ModuleState.Ok(info) else ModuleState.Loading
                }
                .stateIn(scope, SharingStarted.Eagerly, ModuleState.Loading)
                as StateFlow<ModuleState<T>>

            ModuleSpec.Clipboard -> graph.clipboardSync.lastPushedInfo
                .map<ClipboardInfo?, ModuleState<ClipboardInfo>> { info ->
                    if (info != null) ModuleState.Ok(info) else ModuleState.NotFound
                }
                .stateIn(scope, SharingStarted.Eagerly, ModuleState.NotFound)
                as StateFlow<ModuleState<T>>

            ModuleSpec.Files -> graph.fileShareRepo.ownFiles
                .map<FileShareInfo?, ModuleState<FileShareInfo>> { info ->
                    if (info != null) ModuleState.Ok(info) else ModuleState.NotFound
                }
                .stateIn(scope, SharingStarted.Eagerly, ModuleState.NotFound)
                as StateFlow<ModuleState<T>>

            // Power / WiFi / Connectivity / Apps — desktop doesn't collect these.
            else -> MutableStateFlow<ModuleState<T>>(ModuleState.NotFound).asStateFlow()
        }

        /**
         * Remote-peer hot flow: each kick cancels the in-flight read (via [transformLatest])
         * and starts a fresh one. The [readSemaphore] caps cross-slice concurrency. The
         * `WhileSubscribed(stopTimeoutMillis)` tears the pipeline down N seconds after the last
         * collector leaves, so a tile that scrolls offscreen doesn't keep its read alive — but
         * scrolling it back into view within the window does NOT re-fetch (slice replays its
         * last value).
         */
        private fun buildRemoteFlow(stopTimeoutMillis: Long): StateFlow<ModuleState<T>> = kicks
            .transformLatest {
                emit(ModuleState.Loading)
                emit(readOnce())
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = stopTimeoutMillis),
                initialValue = ModuleState.Loading,
            )

        private suspend fun readOnce(): ModuleState<T> {
            if (graph.primaryConnector.value == null) return ModuleState.Loading
            return readSemaphore.withPermit {
                try {
                    when (val result = graph.moduleReader.read(spec.moduleId, deviceId, spec.serializer)) {
                        is ModuleReader.Result.Ok -> ModuleState.Ok(result.value)
                        ModuleReader.Result.NotFound -> ModuleState.NotFound
                        is ModuleReader.Result.Error -> {
                            val cause = result.cause
                            if (cause is OctiServerHttpException && cause.status == HttpStatusCode.TooManyRequests) {
                                // 429 — coarse backoff. Ktor's exception type doesn't surface
                                // Retry-After, so use a fixed window and let the next kick try
                                // again. Don't loop here — the active subscriber will see Error
                                // briefly and a fresh kick from WS/poll will re-attempt.
                                log(TAG, WARN) {
                                    "429 for ${spec.displayName}@${deviceId.logLabel}; backing off ${rateLimitBackoff.inWholeSeconds}s before next read"
                                }
                                delay(rateLimitBackoff.inWholeMilliseconds)
                                return@withPermit ModuleState.Error("rate limited")
                            }
                            ModuleState.Error(cause.message ?: cause.javaClass.simpleName)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                }
            }
        }
    }

    companion object {
        val DEFAULT_POLLING_REFRESH_INTERVAL: Duration = 30.seconds
        val DEFAULT_RATE_LIMIT_BACKOFF: Duration = 30.seconds
    }
}
