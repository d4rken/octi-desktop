package eu.darken.octi.desktop.protocol.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Copy of `sync-core` DeviceId. Wire shape is `{"id": "<uuid>"}`. We drop `@Parcelize` (no
 * navigation arg parceling on desktop).
 */
@Serializable
data class DeviceId(@SerialName("id") val id: String = UUID.randomUUID().toString()) {

    val logLabel: String
        get() = id.take(8)
}
