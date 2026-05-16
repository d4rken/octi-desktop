package eu.darken.octi.desktop.protocol.serialization.serializer

import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object LocaleSerializer : KSerializer<Locale> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.util.Locale", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Locale) = encoder.encodeString(value.toLanguageTag())

    override fun deserialize(decoder: Decoder): Locale = Locale.forLanguageTag(decoder.decodeString())
}
