package eu.darken.octi.desktop.protocol.modules.meta

import eu.darken.octi.desktop.protocol.serialization.serializer.InstantSerializer
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Device identity / OS info. Android-shaped (the Android version/api/security fields are
 * Android-only concepts) — the desktop emits placeholder values for those per the MVP plan.
 *
 * If MetaInfo v2 is added later with a `platform` discriminator and nullable Android fields,
 * update both sides together (this file + app-main + golden fixtures).
 */
@Serializable
data class MetaInfo(
    @SerialName("deviceLabel") val deviceLabel: String?,

    @SerialName("deviceId") val deviceId: DeviceId,

    @SerialName("octiVersionName") val octiVersionName: String,
    @SerialName("octiGitSha") val octiGitSha: String,

    @SerialName("deviceManufacturer") val deviceManufacturer: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("deviceType") val deviceType: DeviceType,
    @Serializable(with = InstantSerializer::class) @SerialName("deviceBootedAt") val deviceBootedAt: Instant,

    @SerialName("androidVersionName") val androidVersionName: String,
    @SerialName("androidApiLevel") val androidApiLevel: Int,
    @SerialName("androidSecurityPatch") val androidSecurityPatch: String?,
) {

    val labelOrFallback: String
        get() = deviceLabel ?: deviceName

    @Serializable
    enum class DeviceType {
        @SerialName("PHONE") PHONE,
        @SerialName("TABLET") TABLET,
        @SerialName("UNKNOWN") UNKNOWN,
    }
}
