package eu.darken.octi.desktop.protocol.sync

import eu.darken.octi.desktop.protocol.module.ModuleId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.ByteString
import java.util.UUID

/** Write-side projection of a sync connector — what the desktop emits per module. */
interface SyncWrite {
    val writeId: UUID
    val deviceId: DeviceId
    val modules: Collection<Device.Module>

    @Serializable
    data class BlobAttachment(
        @SerialName("logicalKey") val logicalKey: String,
        @SerialName("connectorRefs") val connectorRefs: Map<String, RemoteBlobRef> = emptyMap(),
        /**
         * Connectors (by [ConnectorId.idString]) currently advertised as holding this blob. When
         * non-empty, the OctiServer commit filters its refs to only those for connectors in this
         * set — defense-in-depth against stale [connectorRefs] entries. Empty means "no filter"
         * (legacy attachments from pre-`availableOn` producers).
         */
        @SerialName("availableOn") val availableOn: Set<String> = emptySet(),
    )

    interface Device {
        interface Module {
            val moduleId: ModuleId
            val payload: ByteString

            /** Blob attachments. `null` = legacy raw-bytes write; non-null = blob-aware commit. */
            val blobs: List<BlobAttachment>? get() = null
        }
    }
}
