package eu.darken.octi.desktop.protocol.octiserver.dto

import eu.darken.octi.desktop.protocol.serialization.serializer.InstantSerializer
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Wire DTOs for OctiServer REST endpoints. Mirror of nested types in app-main's
 * `OctiServerApi.kt`. All `@SerialName`s must match byte-for-byte — these are wire-stable.
 */

@Serializable
data class RegisterResponse(
    @SerialName("account") val accountID: String,
    @SerialName("password") val password: String,
)

@Serializable
data class ShareCodeResponse(
    @SerialName("code") val shareCode: String,
)

@Serializable
data class DevicesResponse(
    @SerialName("devices") val devices: List<Device>,
) {
    @Serializable
    data class Device(
        @SerialName("id") val id: String,
        @SerialName("version") val version: String? = null,
        @SerialName("platform") val platform: String? = null,
        @SerialName("label") val label: String? = null,
        @Serializable(with = InstantSerializer::class) @SerialName("addedAt") val addedAt: Instant? = null,
        @Serializable(with = InstantSerializer::class) @SerialName("lastSeen") val lastSeen: Instant? = null,
    )
}

@Serializable
data class ResetRequest(
    @SerialName("targets") val targets: Set<DeviceId>,
)

// --- Resumable upload sessions ---

@Serializable
data class CreateSessionRequest(
    @SerialName("sizeBytes") val sizeBytes: Long,
    @SerialName("hashAlgorithm") val hashAlgorithm: String? = null,
    @SerialName("hashHex") val hashHex: String? = null,
)

@Serializable
data class CreateSessionResponse(
    @SerialName("blobId") val blobId: String,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("offsetBytes") val offsetBytes: Long,
    @Serializable(with = InstantSerializer::class) @SerialName("expiresAt") val expiresAt: Instant,
    @SerialName("state") val state: String,
)

@Serializable
data class FinalizeSessionRequest(
    @SerialName("hashAlgorithm") val hashAlgorithm: String,
    @SerialName("hashHex") val hashHex: String,
)

@Serializable
data class FinalizeSessionResponse(
    @SerialName("blobId") val blobId: String,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("sizeBytes") val sizeBytes: Long,
    @SerialName("state") val state: String,
)

// --- Blob list ---

@Serializable
data class BlobListResponse(
    @SerialName("moduleEtag") val moduleEtag: String,
    @SerialName("blobs") val blobs: List<BlobEntry>,
) {
    @Serializable
    data class BlobEntry(
        @SerialName("blobId") val blobId: String,
        @SerialName("sizeBytes") val sizeBytes: Long,
        @SerialName("hashAlgorithm") val hashAlgorithm: String? = null,
        @SerialName("hashHex") val hashHex: String? = null,
    )
}

// --- Module commit (PUT) ---

@Serializable
data class ModuleCommitRequest(
    @SerialName("documentBase64") val documentBase64: String,
    @SerialName("blobRefs") val blobRefs: List<BlobRef>,
) {
    @Serializable
    data class BlobRef(
        @SerialName("blobId") val blobId: String,
    )
}

// --- Account storage ---

@Serializable
data class AccountStorageResponse(
    @SerialName("storageApiVersion") val storageApiVersion: Int,
    @SerialName("accountQuotaBytes") val accountQuotaBytes: Long,
    @SerialName("usedBytes") val usedBytes: Long,
    @SerialName("availableBytes") val availableBytes: Long,
    @SerialName("maxBlobBytes") val maxBlobBytes: Long,
)

// --- Blob session HEAD probe (Codex review #10 — used by resilient PATCH retry) ---

/**
 * Server returns session state via response headers; parsed by the client. Kept as a data class
 * for the client to surface to callers.
 */
data class SessionStatus(
    val sessionId: String,
    val uploadOffset: Long,
    val uploadLength: Long?,
    val uploadExpires: Instant?,
    val uploadState: String,
    val blobId: String,
)
