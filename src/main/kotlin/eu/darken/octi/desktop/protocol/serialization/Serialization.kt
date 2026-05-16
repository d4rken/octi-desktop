package eu.darken.octi.desktop.protocol.serialization

import eu.darken.octi.desktop.protocol.serialization.serializer.ByteStringSerializer
import eu.darken.octi.desktop.protocol.serialization.serializer.DurationSerializer
import eu.darken.octi.desktop.protocol.serialization.serializer.InstantSerializer
import eu.darken.octi.desktop.protocol.serialization.serializer.LocaleSerializer
import eu.darken.octi.desktop.protocol.serialization.serializer.RegexSerializer
import eu.darken.octi.desktop.protocol.serialization.serializer.UUIDSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Desktop equivalent of `app-common/.../SerializationModule.kt`.
 *
 * The config must match Android byte-for-byte for wire compatibility:
 * - `ignoreUnknownKeys = true` — forward-compat for new fields added on the other side
 * - `explicitNulls = false` — Android omits `null` fields entirely; we must too
 * - `encodeDefaults = true` — default values are emitted (the server reads them)
 *
 * `UriSerializer` is omitted: `android.net.Uri` doesn't exist on desktop, and no shared wire type
 * uses it on Android either (it's local UI state only).
 */
object Serialization {

    private val sharedModule = SerializersModule {
        contextual(ByteString::class, ByteStringSerializer)
        contextual(Instant::class, InstantSerializer)
        contextual(Duration::class, DurationSerializer)
        contextual(UUID::class, UUIDSerializer)
        contextual(Locale::class, LocaleSerializer)
        contextual(Regex::class, RegexSerializer)
    }

    /** Use for all wire payloads and on-disk persistence of @Serializable types. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        serializersModule = sharedModule
    }
}

inline fun <reified T> Json.fromJson(raw: String): T = decodeFromString(raw)

inline fun <reified T> Json.toJson(value: T): String = encodeToString(value)

inline fun <reified T> Json.fromJson(raw: ByteString): T = decodeFromString(raw.utf8())

inline fun <reified T> Json.toByteString(value: T): ByteString =
    encodeToString(value).encodeToByteArray().toByteString()
