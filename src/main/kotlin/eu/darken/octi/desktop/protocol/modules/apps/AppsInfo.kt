package eu.darken.octi.desktop.protocol.modules.apps

import eu.darken.octi.desktop.protocol.serialization.serializer.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class AppsInfo(
    @SerialName("installedPackages") val installedPackages: Collection<Pkg>,
) {

    @Serializable
    data class Pkg(
        @SerialName("packageName") val packageName: String,
        @SerialName("label") val label: String?,
        @SerialName("versionCode") val versionCode: Long,
        @SerialName("versionName") val versionName: String?,
        @Serializable(with = InstantSerializer::class) @SerialName("installedAt") val installedAt: Instant,
        @SerialName("installerPkg") val installerPkg: String?,
        @Serializable(with = InstantSerializer::class) @SerialName("updatedAt") val updatedAt: Instant? = null,
    )

    override fun toString(): String = "AppsInfo(size=${installedPackages.size})"
}
