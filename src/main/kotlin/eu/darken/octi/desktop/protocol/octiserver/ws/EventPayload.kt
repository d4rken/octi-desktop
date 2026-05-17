package eu.darken.octi.desktop.protocol.octiserver.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirror of the server's `SyncNotifier.EventPayload`. Wire frames are text JSON of this shape;
 * `events` is a list because the server debounces 500ms and may batch several module changes
 * from the same account into one frame.
 *
 * The polymorphic discriminator is the class name via @SerialName — the server uses `type`
 * as the discriminator field name by default (kotlinx-serialization's default JSON class
 * discriminator). Don't change the SerialName values; they are wire-stable.
 */
@Serializable
data class EventPayload(
    val events: List<Event>,
) {

    @Serializable
    sealed interface Event {

        @Serializable
        @SerialName("module_changed")
        data class ModuleChanged(
            /** Owner of the module that changed (the peer whose data was written). */
            val deviceId: String,
            val moduleId: String,
            val modifiedAt: String,
            val action: String,
            /**
             * Originating device (the actor that wrote). Filter on this to suppress own-write
             * echoes — NOT on [deviceId] (which is the target).
             */
            val sourceDeviceId: String,
        ) : Event
    }
}
