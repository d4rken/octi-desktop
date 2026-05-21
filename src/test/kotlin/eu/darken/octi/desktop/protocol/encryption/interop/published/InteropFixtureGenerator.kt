package eu.darken.octi.desktop.protocol.encryption.interop.published

import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.encryption.interop.InteropFixtures
import eu.darken.octi.desktop.protocol.encryption.interop.PublishedModuleFixture
import eu.darken.octi.desktop.protocol.encryption.interop.PublishedVector
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector.Companion.toConnectorId
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.protocol.sync.RemoteBlobRef
import okio.ByteString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.time.Instant

/**
 * Generates octi-desktop's canonical wire-format fixtures under
 * `src/test/resources/interop/published/`.
 *
 * Run via `./gradlew generateDesktopFixtures`. The output is deterministic — same canonical
 * inputs + same serializer config → same bytes. [InteropFixtureSelfVerifyTest] is the
 * round-trip gate: it re-runs [buildAllFixtures] on every `./gradlew test` and asserts
 * the committed files are byte-equal to what the generator would emit today.
 *
 * Consumers (app-main, octi-web in Phase C2/C3) fetch the committed `octi-desktop-*.json`
 * files at a SHA pinned in their own `fixture-lock.json`, then decode each `payloadJson`
 * vector through their production decoder and assert field-level shape.
 *
 * Mirror of:
 *  - octi-web/tools/generate-fixtures.ts (producer-side B1)
 *  - app-main/sync-core/src/test/.../InteropFixtureGeneratorTest.kt (the crypto-vector producer)
 */
internal object InteropFixtureGenerator {

    const val PRODUCER = "d4rken-org/octi-desktop"
    const val GENERATOR = "src/test/kotlin/.../InteropFixtureGenerator.kt"

    /** Per-file size ceiling. Bound git history growth on regens; flags accidentally-bloated vectors. */
    const val FIXTURE_FILE_SIZE_CEILING = 32 * 1024

    /** Pretty-printed wrapper JSON so reviewers see one vector per line block; payloadJson stays compact. */
    private val wrapperJson: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
        encodeDefaults = true
    }

    /* ─────────────────────── Canonical inputs ─────────────────────── */

    private const val FAUX_DEVICE_ID = "22222222-3333-4444-5555-666666666666"
    private const val FAUX_ACCOUNT_PROD = "77777777-8888-9999-aaaa-bbbbbbbbbbbb"
    private const val FAUX_ACCOUNT_BETA = "cccccccc-1111-2222-3333-444444444444"

    // Connector IDs derived through the SAME function FileShareRepo + MetaWriter use to mint
    // their `connectorRefs`/`availableOn` keys — `OctiServer.Credentials.toConnectorId()`.
    // Routing through the production function means a future format change in `toConnectorId()`
    // (or in ConnectorId.idString) propagates into these fixtures, and the producer self-check
    // trips on regen. Hardcoding a literal — or even calling `ConnectorId(...)` directly —
    // would let the format drift silently. The encryptionKeyset isn't read by toConnectorId()
    // so an empty placeholder is enough.
    private fun fauxConnectorId(domain: String, accountId: String): String {
        val placeholderKeyset = PayloadEncryption.KeySet(type = "AES256_GCM_SIV", key = ByteString.EMPTY)
        return OctiServer.Credentials(
            serverAdress = OctiServer.Address(domain = domain),
            accountId = OctiServer.Credentials.AccountId(id = accountId),
            devicePassword = OctiServer.Credentials.DevicePassword(password = "placeholder"),
            encryptionKeyset = placeholderKeyset,
        ).toConnectorId().idString
    }

    private val FAUX_CONNECTOR: String =
        fauxConnectorId(domain = "prod.kserver.octi.darken.eu", accountId = FAUX_ACCOUNT_PROD)
    private val FAUX_CONNECTOR_BETA: String =
        fauxConnectorId(domain = "beta.kserver.octi.darken.eu", accountId = FAUX_ACCOUNT_BETA)

    private val META_VECTORS: List<Pair<String, MetaInfo>> = listOf(
        // "full" — every field desktop ACTUALLY emits at publish time. Real MetaWriter sets
        // deviceBootedAt to ProcessHandle.current().info().startInstant() — a real Instant
        // (NOT null like web). osType/osVersionName come from System.getProperty("os.name") /
        // "os.version".
        "full" to MetaInfo(
            deviceLabel = "Test Desktop",
            deviceId = DeviceId(FAUX_DEVICE_ID),
            octiVersionName = "0.0.0-test",
            octiGitSha = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            deviceManufacturer = "Eclipse Adoptium",
            deviceName = "octi-test-host",
            deviceType = MetaInfo.DeviceType.DESKTOP,
            deviceBootedAt = Instant.parse("2026-05-01T10:00:00Z"),
            androidVersionName = null,
            androidApiLevel = null,
            androidSecurityPatch = null,
            osType = "Linux",
            osVersionName = "6.8.0",
        ),
        // "minimal" — schema-shape vector: required fields only, deviceBootedAt explicitly
        // null. The real MetaWriter always emits a non-null Instant (it falls back to
        // Clock.System.now() when ProcessHandle.startInstant() is unavailable), so this is
        // NOT a snapshot of real writer output — it's a forward-compat pin for consumers
        // that explicitNulls=false strips deviceBootedAt cleanly.
        "minimal" to MetaInfo(
            deviceLabel = null,
            deviceId = DeviceId(FAUX_DEVICE_ID),
            octiVersionName = "0.0.0-test",
            octiGitSha = "desktop-dev",
            deviceManufacturer = "Eclipse Adoptium",
            deviceName = "octi-desktop",
            deviceType = MetaInfo.DeviceType.DESKTOP,
            deviceBootedAt = null,
        ),
        // "unicode-label" — non-ASCII deviceLabel covering Japanese + emoji + Arabic. Pins
        // the UTF-8 round-trip across the JSON-string escape boundary.
        "unicode-label" to MetaInfo(
            deviceLabel = "デスクトップ 🖥 العربية",
            deviceId = DeviceId(FAUX_DEVICE_ID),
            octiVersionName = "0.0.0-test",
            octiGitSha = "desktop-dev",
            deviceManufacturer = "Eclipse Adoptium",
            deviceName = "octi-desktop",
            deviceType = MetaInfo.DeviceType.DESKTOP,
            deviceBootedAt = Instant.parse("2026-05-01T10:00:00Z"),
            osType = "Linux",
        ),
    )

    private val CLIPBOARD_VECTORS: List<Pair<String, ClipboardInfo>> = listOf(
        "EMPTY" to ClipboardInfo(type = ClipboardInfo.Type.EMPTY),
        "SIMPLE_TEXT_short" to ClipboardInfo(
            type = ClipboardInfo.Type.SIMPLE_TEXT,
            data = "hello from desktop".encodeUtf8(),
        ),
        "SIMPLE_TEXT_unicode" to ClipboardInfo(
            type = ClipboardInfo.Type.SIMPLE_TEXT,
            data = "café 👋 你好 — العربية".encodeUtf8(),
        ),
    )

    // Desktop's FileShareRepo emits `UUID.randomUUID().toString()` for SharedFile.blobKey —
    // NOT the `sha256:<hex>` form Android uses. Fixed canonical UUIDs here so consumers can
    // tell the wire shape apart from app-main's fixtures. `checksum` stays as 64-char hex
    // since that's the SHA-256 of the file's plaintext (same across all producers).
    private val FILES_VECTORS: List<Pair<String, FileShareInfo>> = listOf(
        "empty" to FileShareInfo(),
        "single-file" to FileShareInfo(
            files = listOf(
                FileShareInfo.SharedFile(
                    name = "notes.txt",
                    mimeType = "text/plain",
                    size = 1234L,
                    blobKey = "00000000-0000-0000-0000-000000000001",
                    checksum = "11".repeat(32),
                    sharedAt = Instant.parse("2026-05-01T12:00:00Z"),
                    expiresAt = Instant.parse("2026-05-31T12:00:00Z"),
                    availableOn = setOf(FAUX_CONNECTOR),
                    connectorRefs = mapOf(FAUX_CONNECTOR to RemoteBlobRef("blob-id-aaaa")),
                ),
            ),
        ),
        "with-multiple-files" to FileShareInfo(
            files = listOf(
                FileShareInfo.SharedFile(
                    name = "alpha.bin",
                    mimeType = "application/octet-stream",
                    size = 256L,
                    blobKey = "00000000-0000-0000-0000-000000000002",
                    checksum = "22".repeat(32),
                    sharedAt = Instant.parse("2026-05-01T12:00:00Z"),
                    expiresAt = Instant.parse("2026-05-31T12:00:00Z"),
                    availableOn = setOf(FAUX_CONNECTOR),
                    connectorRefs = mapOf(FAUX_CONNECTOR to RemoteBlobRef("blob-id-bbbb")),
                ),
                FileShareInfo.SharedFile(
                    name = "beta.pdf",
                    mimeType = "application/pdf",
                    size = 4096L,
                    blobKey = "00000000-0000-0000-0000-000000000003",
                    checksum = "33".repeat(32),
                    sharedAt = Instant.parse("2026-05-01T13:00:00Z"),
                    expiresAt = Instant.parse("2026-05-31T13:00:00Z"),
                    availableOn = setOf(FAUX_CONNECTOR),
                    connectorRefs = mapOf(FAUX_CONNECTOR to RemoteBlobRef("blob-id-cccc")),
                ),
            ),
        ),
        "with-delete-requests" to FileShareInfo(
            files = listOf(
                FileShareInfo.SharedFile(
                    name = "shared.txt",
                    mimeType = "text/plain",
                    size = 100L,
                    blobKey = "00000000-0000-0000-0000-000000000004",
                    checksum = "44".repeat(32),
                    sharedAt = Instant.parse("2026-05-01T12:00:00Z"),
                    expiresAt = Instant.parse("2026-05-31T12:00:00Z"),
                    availableOn = setOf(FAUX_CONNECTOR),
                    connectorRefs = mapOf(FAUX_CONNECTOR to RemoteBlobRef("blob-id-dddd")),
                ),
            ),
            deleteRequests = listOf(
                FileShareInfo.DeleteRequest(
                    targetDeviceId = "99999999-8888-7777-6666-555555555555",
                    blobKey = "00000000-0000-0000-0000-000000000005",
                    requestedAt = Instant.parse("2026-05-10T00:00:00Z"),
                    retainUntil = Instant.parse("2026-05-17T00:00:00Z"),
                ),
            ),
        ),
        "multi-connector" to FileShareInfo(
            // Two connectors hosting the same blobKey — pins the multi-connector wire shape
            // (availableOn list + connectorRefs map keyed by connector id). FileShareRepo's
            // connector-merge code reads connectorRefs[connector.connectorId] on download; a
            // single-connector vector wouldn't catch a future bug hardcoding one key.
            files = listOf(
                FileShareInfo.SharedFile(
                    name = "shared-across.bin",
                    mimeType = "application/octet-stream",
                    size = 512L,
                    blobKey = "00000000-0000-0000-0000-000000000007",
                    checksum = "77".repeat(32),
                    sharedAt = Instant.parse("2026-05-01T12:00:00Z"),
                    expiresAt = Instant.parse("2026-05-31T12:00:00Z"),
                    availableOn = setOf(FAUX_CONNECTOR, FAUX_CONNECTOR_BETA),
                    connectorRefs = mapOf(
                        FAUX_CONNECTOR to RemoteBlobRef("blob-id-prod-7777"),
                        FAUX_CONNECTOR_BETA to RemoteBlobRef("blob-id-beta-7777"),
                    ),
                ),
            ),
        ),
        "files-large" to FileShareInfo(
            files = listOf(
                FileShareInfo.SharedFile(
                    // size > Int.MAX_VALUE (2^31 - 1 = 2147483647) pins Long handling on
                    // consumers. 8 GB is comfortably above the boundary; JVM Long handles
                    // it exactly.
                    name = "big.iso",
                    mimeType = "application/octet-stream",
                    size = 8_000_000_000L,
                    blobKey = "00000000-0000-0000-0000-000000000006",
                    checksum = "66".repeat(32),
                    sharedAt = Instant.parse("2026-05-01T12:00:00Z"),
                    expiresAt = Instant.parse("2026-05-31T12:00:00Z"),
                    availableOn = setOf(FAUX_CONNECTOR),
                    connectorRefs = mapOf(FAUX_CONNECTOR to RemoteBlobRef("blob-id-eeee")),
                ),
            ),
        ),
    )

    /* ─────────────────────── Build ─────────────────────── */

    /**
     * Pure: build the manifest + all per-module fixture bytes. Called both by the regenerator
     * task ([InteropFixtureGeneratorTest]) and by [InteropFixtureSelfVerifyTest].
     */
    fun buildAllFixtures(): GeneratedFixtures {
        val files = listOf(
            buildModuleFixture(
                fileName = "octi-desktop-meta.json",
                moduleId = ModuleIds.META.id,
                note = "Canonical MetaInfo payloads octi-desktop emits. Consumers (octi, octi-web) " +
                    "must decode each `payloadJson` through their MetaInfo decoder.",
                vectors = META_VECTORS,
                serializer = MetaInfo.serializer(),
            ),
            buildModuleFixture(
                fileName = "octi-desktop-clipboard.json",
                moduleId = ModuleIds.CLIPBOARD.id,
                note = "Canonical ClipboardInfo payloads octi-desktop emits. Pin: type+data base64 wire shape.",
                vectors = CLIPBOARD_VECTORS,
                serializer = ClipboardInfo.serializer(),
            ),
            buildModuleFixture(
                fileName = "octi-desktop-files.json",
                moduleId = ModuleIds.FILES.id,
                note = "Canonical FileShareInfo payloads octi-desktop emits. Includes a >Int.MAX_VALUE " +
                    "size vector to pin Long handling on consumers + a multi-connector vector.",
                vectors = FILES_VECTORS,
                serializer = FileShareInfo.serializer(),
            ),
        )

        // Manifest sorted by filename so the JSON is order-stable across regenerations. Use a
        // codepoint compare (not locale-aware) for determinism across JVM ICU builds.
        val sortedFiles = files.sortedBy { it.name }
        val manifestFiles = LinkedHashMap<String, ManifestEntry>(sortedFiles.size)
        for (f in sortedFiles) {
            manifestFiles[f.name] = ManifestEntry(
                sha256 = InteropFixtures.sha256Hex(f.bytes),
                byteLength = f.bytes.size,
            )
        }
        val manifestObj = buildJsonObject {
            put("schemaVersion", InteropFixtures.SCHEMA_VERSION)
            put("source", PRODUCER)
            put("generator", GENERATOR)
            put("files", buildJsonObject {
                for ((name, entry) in manifestFiles) {
                    put(name, buildJsonObject {
                        put("sha256", entry.sha256)
                        put("byteLength", entry.byteLength)
                    })
                }
            })
        }
        // Trailing newline to match B1's output convention; reviewers expect POSIX-line-ending files.
        val manifestBytes = (wrapperJson.encodeToString(JsonObject.serializer(), manifestObj) + "\n")
            .toByteArray(Charsets.UTF_8)

        return GeneratedFixtures(
            manifestBytes = manifestBytes,
            manifestEntries = manifestFiles,
            files = files,
        )
    }

    private fun <T> buildModuleFixture(
        fileName: String,
        moduleId: String,
        note: String,
        vectors: List<Pair<String, T>>,
        serializer: KSerializer<T>,
    ): GeneratedFile {
        val built = vectors.map { (name, input) ->
            val bytes = Serialization.json.encodeToString(serializer, input).toByteArray(Charsets.UTF_8)
            PublishedVector(
                name = name,
                payloadJson = bytes.toString(Charsets.UTF_8),
                sha256 = InteropFixtures.sha256Hex(bytes),
                byteLength = bytes.size,
            )
        }
        val content = PublishedModuleFixture(
            schemaVersion = InteropFixtures.SCHEMA_VERSION,
            module = moduleId,
            producer = PRODUCER,
            note = note,
            vectors = built,
        )
        val json = wrapperJson.encodeToString(PublishedModuleFixture.serializer(), content) + "\n"
        return GeneratedFile(name = fileName, bytes = json.toByteArray(Charsets.UTF_8), content = content)
    }
}

internal data class ManifestEntry(val sha256: String, val byteLength: Int)

internal data class GeneratedFile(
    val name: String,
    val bytes: ByteArray,
    val content: PublishedModuleFixture,
) {
    // Default equals/hashCode on ByteArray uses identity, which breaks set-based comparisons.
    // We never compare GeneratedFile instances for equality (only the bytes themselves).
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

internal data class GeneratedFixtures(
    val manifestBytes: ByteArray,
    val manifestEntries: Map<String, ManifestEntry>,
    val files: List<GeneratedFile>,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
