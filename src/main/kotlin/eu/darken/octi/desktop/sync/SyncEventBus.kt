package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus for inbound sync events. The [OctiServerWebSocketClient] emits decoded
 * [EventPayload.Event]s here after self-suppression; repos subscribe to invalidate their
 * caches and trigger targeted refreshes.
 *
 * `replay = 0`, `extraBufferCapacity = 32` — we don't replay history to late subscribers, but
 * we don't want to drop events if a slow consumer falls a frame or two behind.
 */
class SyncEventBus {

    private val _events = MutableSharedFlow<EventPayload.Event>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<EventPayload.Event> = _events.asSharedFlow()

    suspend fun emit(event: EventPayload.Event) = _events.emit(event)
}
