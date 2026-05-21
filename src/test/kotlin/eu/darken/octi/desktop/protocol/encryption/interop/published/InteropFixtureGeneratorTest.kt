package eu.darken.octi.desktop.protocol.encryption.interop.published

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regenerator. Disabled by default — a plain `./gradlew test` does NOT rewrite committed
 * fixtures. Enabled when `generateInteropFixtures=true` is set on the test JVM, which the
 * `generateDesktopFixtures` Gradle task wires up.
 *
 * Mirror of app-main's `InteropFixtureGeneratorTest` (the crypto-vector regenerator). The
 * always-on self-check ([InteropFixtureSelfVerifyTest]) keeps producer + committed in lockstep
 * so a contributor changing canonical inputs without regenerating fails CI immediately.
 *
 * Regenerate via:
 *
 *   ./gradlew generateDesktopFixtures
 */
class InteropFixtureGeneratorTest {

    @Test
    @EnabledIfSystemProperty(named = "generateInteropFixtures", matches = "true")
    fun `regenerate published fixtures and write them to disk`() {
        val outDir = resolveOutDir()
        Files.createDirectories(outDir)
        val generated = InteropFixtureGenerator.buildAllFixtures()

        for (file in generated.files) {
            check(file.bytes.size <= InteropFixtureGenerator.FIXTURE_FILE_SIZE_CEILING) {
                "fixture ${file.name} is ${file.bytes.size} bytes, exceeds ceiling " +
                    "${InteropFixtureGenerator.FIXTURE_FILE_SIZE_CEILING}"
            }
            Files.write(outDir.resolve(file.name), file.bytes)
            println("  wrote ${file.name} (${file.bytes.size} bytes)")
        }
        // Manifest is subject to the same ceiling — a hostile or accidentally-bloated manifest
        // shouldn't slip past just because it's structurally separate from the module files.
        check(generated.manifestBytes.size <= InteropFixtureGenerator.FIXTURE_FILE_SIZE_CEILING) {
            "manifest.json is ${generated.manifestBytes.size} bytes, exceeds ceiling " +
                "${InteropFixtureGenerator.FIXTURE_FILE_SIZE_CEILING}"
        }
        Files.write(outDir.resolve("manifest.json"), generated.manifestBytes)
        println("  wrote manifest.json (${generated.manifestBytes.size} bytes)")
        println("fixtures written to $outDir")
    }

    private fun resolveOutDir(): Path {
        // Gradle's generateDesktopFixtures task passes `-DinteropFixturesOutDir=<path>`. When
        // unset (e.g. an IDE re-run), default to the on-disk committed location so the
        // regenerator's output matches what the self-verify test reads.
        System.getProperty("interopFixturesOutDir")?.let { return Path.of(it).toAbsolutePath() }
        val repoRoot = System.getProperty("interopRepoRoot")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.dir"))
        return repoRoot.resolve("src/test/resources/interop/published").toAbsolutePath()
    }
}
