package eu.darken.octi.desktop.protocol.encryption.interop.published

import eu.darken.octi.desktop.protocol.encryption.interop.InteropFixtures
import eu.darken.octi.desktop.protocol.encryption.interop.PublishedModuleFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Always-on self-check for the canonical fixtures octi-desktop publishes under
 * `src/test/resources/interop/published/`.
 *
 * Runs on every `./gradlew test`. Reads the committed JSON files, re-runs [InteropFixtureGenerator]
 * (a pure function), and asserts byte-equality for every file plus per-vector sha256+byteLength
 * integrity. Catches drift between (a) canonical inputs, (b) what the serializers actually
 * produce, and (c) what's committed on disk.
 *
 * Regenerate via `./gradlew generateDesktopFixtures` whenever any of those three legs is
 * intentionally moved.
 */
class InteropFixtureSelfVerifyTest {

    private val sha256Re = Regex("^[a-f0-9]{64}$")
    private val expectedFiles = listOf(
        "octi-desktop-clipboard.json",
        "octi-desktop-files.json",
        "octi-desktop-meta.json",
    )

    @Test
    fun `manifest matches committed bytes`() {
        val generated = InteropFixtureGenerator.buildAllFixtures()
        val committed = readCommitted("manifest.json")
        committed.toList() shouldBe generated.manifestBytes.toList()
    }

    @Test
    fun `manifest references exactly the expected file set`() {
        val generated = InteropFixtureGenerator.buildAllFixtures()
        generated.manifestEntries.keys.sorted() shouldBe expectedFiles
        for ((_, entry) in generated.manifestEntries) {
            sha256Re.matches(entry.sha256) shouldBe true
            (entry.byteLength > 0) shouldBe true
        }
    }

    @Test
    fun `per-module files match committed bytes`() {
        val generated = InteropFixtureGenerator.buildAllFixtures()
        for (file in generated.files) {
            val committed = readCommitted(file.name)
            // Byte-equality: regenerating must produce the same JSON the committed file holds.
            // Failing here means a canonical input, a serializer's output, or the committed
            // file drifted — regenerate via `./gradlew generateDesktopFixtures` after
            // confirming the change is intentional.
            committed.toList() shouldBe file.bytes.toList()
        }
    }

    @Test
    fun `per-module manifest entries match committed bytes + lengths`() {
        for (file in expectedFiles) {
            val committed = readCommitted(file)
            val generated = InteropFixtureGenerator.buildAllFixtures()
            val entry = generated.manifestEntries[file]!!
            InteropFixtures.sha256Hex(committed) shouldBe entry.sha256
            committed.size shouldBe entry.byteLength
        }
    }

    @Test
    fun `every committed file is under the size ceiling`() {
        for (file in expectedFiles + "manifest.json") {
            val committed = readCommitted(file)
            (committed.size <= InteropFixtureGenerator.FIXTURE_FILE_SIZE_CEILING) shouldBe true
        }
    }

    @Test
    fun `every vector self-consistency holds`() {
        val generated = InteropFixtureGenerator.buildAllFixtures()
        for (file in generated.files) {
            val parsed = InteropFixtures.json.decodeFromString(
                PublishedModuleFixture.serializer(),
                file.bytes.toString(Charsets.UTF_8),
            )
            parsed.schemaVersion shouldBe InteropFixtures.SCHEMA_VERSION
            (parsed.module.startsWith("eu.darken.octi.module.core.")) shouldBe true
            parsed.producer shouldBe InteropFixtureGenerator.PRODUCER
            (parsed.note.isNotEmpty()) shouldBe true
            (parsed.vectors.isNotEmpty()) shouldBe true

            val names = parsed.vectors.map { it.name }
            names.toSet().size shouldBe names.size

            for (v in parsed.vectors) {
                (v.name.isNotEmpty()) shouldBe true
                sha256Re.matches(v.sha256) shouldBe true
                (v.byteLength >= 0) shouldBe true
                val bytes = v.payloadJson.toByteArray(Charsets.UTF_8)
                v.byteLength shouldBe bytes.size
                v.sha256 shouldBe InteropFixtures.sha256Hex(bytes)
            }
        }
    }

    private fun readCommitted(name: String): ByteArray {
        return Files.readAllBytes(committedDir().resolve(name))
    }

    private fun committedDir(): Path {
        // Gradle's tasks.test passes -DinteropRepoRoot=<projectDir>. user.dir fallback covers
        // IDE-direct runs from the project root.
        val repoRoot = System.getProperty("interopRepoRoot")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.dir"))
        return repoRoot.resolve("src/test/resources/interop/published").toAbsolutePath()
    }
}
