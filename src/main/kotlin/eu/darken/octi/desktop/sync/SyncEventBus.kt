package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Process-wide bus for inbound sync events. The [OctiServerWebSocketClient] emits decoded
 * [EventPayload.Event]s here after self-suppression; repos subscribe to invalidate their
 * caches and trigger targeted refreshes.
 *
 * Events carry the [ConnectorId] of the connector that produced them so multi-connector
 * consumers (most notably the future module resolver cache) can scope invalidation to the
 * right source. Single-connector consumers can ignore [SyncEvent.connectorId] entirely and
 * just react to any incoming event.
 *
 * `replay = 0`, `extraBufferCapacity = 32` — we don't replay history to late subscribers, but
 * we don't want to drop events if a slow consumer falls a frame or two behind.
 *
 * [lastEventAt] is the timestamp of the most recent successful emit, used by the debug RPC
 * `/dev/state` endpoint as a coarse "are events still flowing?" signal. Null until the first
 * event arrives.
 */
class SyncEventBus {

    private val _events = MutableSharedFlow<SyncEvent>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    private val _lastEventAt = MutableStateFlow<Instant?>(null)
    val lastEventAt: StateFlow<Instant?> = _lastEventAt.asStateFlow()

    suspend fun emit(connectorId: ConnectorId, event: EventPayload.Event) {
        _events.emit(SyncEvent(connectorId, event))
        _lastEventAt.value = Clock.System.now()
    }
}

/**
 * Bus envelope: which connector reported [event]. Consumers that need to scope invalidation
 * per-source (e.g. module resolver cache) use [connectorId]; consumers that just want a "data
 * changed somewhere" signal can ignore it.
 */
data class SyncEvent(
    val connectorId: ConnectorId,
    val event: EventPayload.Event,
)
