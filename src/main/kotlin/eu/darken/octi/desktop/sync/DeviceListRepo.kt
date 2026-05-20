package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private val TAG = logTag("Sync", "DeviceListRepo")

/**
 * Source of truth for "which devices are in this account?". Polls `/v1/devices` against every
 * non-paused connector in [AppGraph.runningConnectors] concurrently, falls back to the on-disk
 * cache on cold start, and tolerates transient fetch failures per connector (keeps emitting
 * the last-known list rather than erroring out the UI).
 *
 * Per-connector polling loops are torn down via `flatMapLatest` whenever the set of running
 * connectors changes (link/unlink/pause toggle) — no leaked coroutines, no requests against a
 * closed [OctiServerConnector]. [kick] is a broadcast: a single refresh wakes every active
 * loop, not just one.
 *
 * Paused connectors don't poll, but their last-known devices stay visible in [mergedDevices]
 * so the dashboard doesn't blank out when the user pauses — only [activeConnectors] going
 * fully empty (unlink of every connector) prunes the state.
 */
class DeviceListRepo(
    private val graph: AppGraph,
    private val pollIntervalSeconds: Int = graph.settings.data.syncIntervalSeconds,
    private val cache: DeviceListCache = DeviceListCache(),
) {

    private val _loadStateByConnector = MutableStateFlow<Map<ConnectorId, LoadState>>(emptyMap())

    /**
     * Per-connector poll state. Empty map = no connectors active. Each connector tracks its own
     * state independently — a transient error on one doesn't drag the others. Today 0-or-1
     * entries; multi-connector ready.
     */
    val loadStateByConnector: StateFlow<Map<ConnectorId, LoadState>> = _loadStateByConnector.asStateFlow()

    /**
     * Per-connector raw device lists. Today 0-or-1 keys; merge happens in [mergedDevices].
     * Keys are [ConnectorId.idString] for parity with the on-disk cache map shape (and so the
     * debug-rpc payload doesn't have to re-stringify).
     */
    private val _perConnector = MutableStateFlow<Map<String, List<DevicesResponse.Device>>>(emptyMap())

    /**
     * Broadcast refresh signal. Every active poll loop subscribes via `kickFlow.first()`, so a
     * single [kick] wakes all connectors. `extraBufferCapacity = 16` is generous — bursts of
     * kicks within one poll window are debounced naturally by the loops sleeping on `first()`,
     * and the buffer ensures `tryEmit` never drops a kick under load.
     */
    private val kickFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** Manually request an immediate refresh on every active poll loop. */
    fun kick() {
        kickFlow.tryEmit(Unit)
    }

    /**
     * Per-connector slice emitter. Switches connector set via `flatMapLatest` (a new link or
     * unlink restarts the merged stream) and uses [merge] inside so all N loops run
     * concurrently. Emits `(idString → devices)` deltas; the collector below folds them into
     * [_perConnector].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val perConnectorPipe: Flow<Pair<String, List<DevicesResponse.Device>>> =
        graph.runningConnectors.flatMapLatest { connectors ->
            if (connectors.isEmpty()) emptyFlow()
            else merge(*connectors.map { pollLoopForConnector(it) }.toTypedArray())
        }

    /**
     * Merged view across all configured connectors. UI consumes this; today it always has one
     * source per device, but when GDrive lands the same flat list keeps shape and `sources`
     * tells consumers (debug RPC, future routing UI) which connectors saw which device.
     */
    val mergedDevices: StateFlow<List<MergedDevice>> = _perConnector
        .map { perConnectorMap ->
            // Re-key from idString → ConnectorId for the merge function. The structured ids
            // live in settings.connectors; if a per-connector entry has no settings entry
            // (shouldn't happen — Step 3 enforces both stores) we drop it.
            val structured: Map<ConnectorId, List<DevicesResponse.Device>> = perConnectorMap
                .mapNotNull { (idString, devices) ->
                    val structuredId = graph.settings.data.connectors[idString]?.connectorId
                    if (structuredId == null) {
                        log(TAG, Logging.Priority.WARN) {
                            "perConnector key $idString has no settings.connectors entry — skipping"
                        }
                        null
                    } else {
                        structuredId to devices
                    }
                }
                .toMap()
            mergeDeviceLists(structured)
        }
        .stateIn(graph.appScope, SharingStarted.Eagerly, emptyList())

    /**
     * Flat device list for UI back-compat. Equivalent to `mergedDevices.map { it.device }` —
     * preserved as its own StateFlow so existing collectors don't need to change shape.
     */
    val devices: StateFlow<List<DevicesResponse.Device>> = mergedDevices
        .map { it.map { merged -> merged.device } }
        .stateIn(
            scope = graph.appScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /**
     * Aggregate load state across all configured connectors. UI consumes this for "show
     * spinner / error / show data" decisions; multi-connector-safe ahead of the dashboard
     * gating change ([Step 9 in the multi-connector-ready refactor]):
     *  - empty map (no connectors active) → [LoadState.Loading] (transient unlink window)
     *  - any connector errored → that error (first one wins; today there's only one)
     *  - any connector loading → [LoadState.Loading]
     *  - all connectors Ok → [LoadState.Ok]
     */
    val loadState: StateFlow<LoadState> = _loadStateByConnector
        .map { map ->
            when {
                map.isEmpty() -> LoadState.Loading
                map.values.any { it is LoadState.Error } ->
                    map.values.first { it is LoadState.Error }
                map.values.any { it is LoadState.Loading } -> LoadState.Loading
                else -> LoadState.Ok
            }
        }
        .stateIn(graph.appScope, SharingStarted.Eagerly, LoadState.Loading)

    init {
        // Cold-start seed from cache: load whatever was persisted on the last run, filtered to
        // configured connectors. The async pipe takes over once a connector emits.
        val seeded = cache.load(knownConnectorIds = graph.settings.data.connectors.keys)
        _perConnector.value = seeded

        // Drive per-connector slices into the published state. Each emission is a single
        // connector's update; we fold via `update { it + delta }` so concurrent emissions
        // from N loops don't clobber each other.
        perConnectorPipe
            .onEach { (key, devices) ->
                val updated = _perConnector.value + (key to devices)
                _perConnector.value = updated
                cache.saveAll(updated)
            }
            .launchIn(graph.appScope)

        // Prune state for connectors that left activeConnectors entirely (unlink). Note: paused
        // connectors stay in activeConnectors, so their cached devices remain visible — only a
        // full unlink prunes. Empty list also wipes the persisted cache so a future link with a
        // different account doesn't show ghost devices.
        graph.activeConnectors
            .onEach { connectors ->
                val activeKeys = connectors.map { it.identifier.idString }.toSet()
                val activeIds = connectors.map { it.identifier }.toSet()
                _perConnector.value = _perConnector.value.filterKeys { it in activeKeys }
                _loadStateByConnector.value = _loadStateByConnector.value.filterKeys { it in activeIds }
                if (connectors.isEmpty()) cache.clear()
            }
            .launchIn(graph.appScope)

        // WS-driven freshness: any incoming sync event means at least one peer wrote a module,
        // which means at least one peer's lastSeen advanced. Kick the polling loop so the
        // Dashboard's "Online" badges update without waiting for the 5-min REST tick.
        graph.syncEventBus.events
            .onEach { kick() }
            .launchIn(graph.appScope)
    }

    private fun pollLoopForConnector(
        connector: OctiServerConnector,
    ): Flow<Pair<String, List<DevicesResponse.Device>>> = flow {
        val id = connector.identifier
        val key = id.idString
        while (true) {
            _loadStateByConnector.update { it + (id to LoadState.Loading) }
            try {
                val response = connector.client.getDeviceList()
                _loadStateByConnector.update { it + (id to LoadState.Ok) }
                emit(key to response.devices)
            } catch (e: Throwable) {
                log(TAG, Logging.Priority.WARN, e) { "getDeviceList for ${id.idString} failed; keeping last value" }
                _loadStateByConnector.update {
                    it + (id to LoadState.Error(e.message ?: e.javaClass.simpleName))
                }
            }
            // Sleep until either the poll interval expires or `kick()` is called. `first()` on
            // the broadcast SharedFlow wakes all per-connector loops simultaneously.
            withTimeoutOrNull(pollIntervalSeconds.seconds) { kickFlow.first() }
        }
    }

    private fun <K, V> MutableStateFlow<Map<K, V>>.update(transform: (Map<K, V>) -> Map<K, V>) {
        value = transform(value)
    }

    sealed class LoadState {
        data object Loading : LoadState()
        data object Ok : LoadState()
        data class Error(val message: String) : LoadState()
    }
}
