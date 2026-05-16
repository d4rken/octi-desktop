package eu.darken.octi.desktop.protocol.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client-side logical identity of a blob. Stable across connectors; minted once on upload by the
 * creating device. Persisted in [SyncWrite.BlobAttachment.logicalKey] and in the module payload
 * (e.g. `FileShareInfo.SharedFile.blobKey`).
 */
@Serializable
data class BlobKey(@SerialName("id") val id: String)

/**
 * Connector-scoped remote reference. Opaque string the backend uses to locate a blob.
 *
 * - GDrive: equals [BlobKey.id] (filename under `blob-store/{deviceId}/{moduleId}/`)
 * - OctiServer: server-generated blob id returned from `finalize` — **not** equal to [BlobKey.id]
 *
 * Callers must never parse this; only round-trip it back to the matching connector.
 */
@JvmInline
@Serializable
value class RemoteBlobRef(val value: String)
