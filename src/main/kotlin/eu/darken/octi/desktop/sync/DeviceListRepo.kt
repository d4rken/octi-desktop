package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private val TAG = logTag("Sync", "DeviceListRepo")

/**
 * Source of truth for "which devices are in this account?". Polls `/v1/devices` against every
 * connector in [AppGraph.activeConnectors] while they're active, falls back to the on-disk
 * cache on cold start, and tolerates transient fetch failures per connector (keeps emitting
 * the last-known list rather than erroring out the UI).
 *
 * Per-connector polling loops are torn down via `flatMapLatest` whenever the set of active
 * connectors changes — no leaked coroutines, no requests against a closed
 * [OctiServerConnector].
 *
 * Today the active-connector set is always 0-or-1, but the merge step and the per-connector
 * state maps are real: when GDrive lands, [mergedDevices] keeps surfacing the same flat list
 * to the UI while the underlying per-connector loops fan out.
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
     * Wakes the polling loop early. `CONFLATED` capacity means a burst of N kicks within one
     * poll window collapses to a single refresh — the server's 500ms debounce already groups
     * related events, so further collapsing on the client side is correct, not lossy.
     */
    private val kickChannel = Channel<Unit>(Channel.CONFLATED)

    /** Manually request an immediate refresh (UI refresh button, post-link warm-up, …). */
    fun kick() {
        kickChannel.trySend(Unit)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val perConnectorPipe: Flow<Map<String, List<DevicesResponse.Device>>> =
        graph.activeConnectors.flatMapLatest { connectors ->
            // Reset per-connector state when the connector set changes so old entries don't
            // linger. The startup cache seed (in init {}) populates the same shape; this flow
            // overrides it on first emission.
            if (connectors.isEmpty()) {
                _loadStateByConnector.value = emptyMap()
                flowOf(emptyMap())
            } else {
                // Single-connector today: run one poll loop and emit its slice. Multi-connector
                // tomorrow: combine N poll loops via combine{} — for now we serialize the loops
                // since active.size is always 1.
                pollLoopForConnector(connectors.first())
            }
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

        // Drive the per-connector slice into the published state.
        perConnectorPipe
            .onEach { _perConnector.value = it }
            .launchIn(graph.appScope)

        // Side effect: when the connector set becomes empty (unlink), wipe the cache so a
        // future link with a different account doesn't show ghost devices.
        graph.activeConnectors
            .onEach { if (it.isEmpty()) cache.clear() }
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
    ): Flow<Map<String, List<DevicesResponse.Device>>> = flow {
        val id = connector.identifier
        val key = id.idString
        while (true) {
            _loadStateByConnector.update { it + (id to LoadState.Loading) }
            try {
                val response = connector.client.getDeviceList()
                // Persist the updated map BEFORE emitting so the cache and the flow agree.
                val nextPerConnector = _perConnector.value + (key to response.devices)
                cache.saveAll(nextPerConnector)
                _loadStateByConnector.update { it + (id to LoadState.Ok) }
                emit(nextPerConnector)
            } catch (e: Throwable) {
                log(TAG, Logging.Priority.WARN, e) { "getDeviceList for ${id.idString} failed; keeping last value" }
                _loadStateByConnector.update {
                    it + (id to LoadState.Error(e.message ?: e.javaClass.simpleName))
                }
            }
            // withTimeoutOrNull returns null on timeout (natural poll tick) and Unit on a kick.
            // Either way we restart the loop body and refetch.
            withTimeoutOrNull(pollIntervalSeconds.seconds) { kickChannel.receive() }
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
