package eu.darken.octi.desktop.protocol.sync

import eu.darken.octi.desktop.protocol.module.ModuleId
import okio.ByteString
import kotlin.time.Instant

/** Read-side projection of a sync connector — what the desktop sees per peer per module. */
interface SyncRead {
    val connectorId: ConnectorId
    val devices: Collection<Device>

    interface Device {
        val deviceId: DeviceId
        val modules: Collection<Module>

        interface Module {
            val connectorId: ConnectorId
            val deviceId: DeviceId
            val moduleId: ModuleId
            val modifiedAt: Instant
            val payload: ByteString
        }
    }
}
