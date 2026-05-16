package eu.darken.octi.desktop.protocol.modules.clipboard

import eu.darken.octi.desktop.protocol.serialization.serializer.ByteStringSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.ByteString

@Serializable
data class ClipboardInfo(
    @SerialName("type") val type: Type = Type.EMPTY,
    @Serializable(with = ByteStringSerializer::class) @SerialName("data") val data: ByteString = ByteString.EMPTY,
) {

    init {
        if (data.size > MAX_BYTES) throw IllegalArgumentException("Size limit exceeded (>${MAX_BYTES / 1024}KB)")
    }

    @Serializable
    enum class Type {
        @SerialName("EMPTY") EMPTY,
        @SerialName("SIMPLE_TEXT") SIMPLE_TEXT,
    }

    companion object {
        const val MAX_BYTES = 32 * 1024
    }
}
