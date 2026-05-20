package eu.darken.octi.desktop.protocol.encryption.interop

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * Fetch + verify d4rken-org/octi's interop fixtures at the SHA pinned in `fixture-lock.json`.
 * Idempotent — repeated invocations with a populated cache skip the network entirely.
 *
 * Called from [InteropFixtureVerifyTest]'s `@BeforeAll`; designed so multiple test classes
 * (current or future) can invoke [ensureSynced] safely without ever re-downloading.
 *
 * Why this pattern instead of a Gradle task:
 *  - Same lifecycle for `./gradlew test` and IDE-direct test runs — no separate plumbing.
 *  - Failures surface as test failures with the same diagnostic surface area.
 *  - The cache check + sha256 verify is the same trust boundary either way.
 */
internal object InteropFixtureSync {

    private const val FETCH_TIMEOUT_MS = 15_000L
    private const val FETCH_RETRIES = 1

    private const val EXPECTED_SOURCE = "d4rken-org/octi"

    // Filenames: ASCII subset, must end in `.json`, no leading dot, no dot segments, no `..`.
    private val FIXTURE_FILE_RE = Regex(
        """^(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\.json$""",
    )
    private val RESERVED_FILENAMES = setOf(".sha", "manifest.json")
    private val SHA40_RE = Regex("""^[a-f0-9]{40}$""")
    private val SHA256_RE = Regex("""^[a-f0-9]{64}$""")

    @Volatile private var cacheDirCached: Path? = null

    /**
     * Returns the resolved cache directory for the locked SHA, populating it from upstream if
     * needed. Safe to call from multiple test classes — only the first call does work.
     */
    fun ensureSynced(): Path {
        cacheDirCached?.let { return it }
        synchronized(this) {
            cacheDirCached?.let { return it }
            val dir = runSync()
            cacheDirCached = dir
            return dir
        }
    }

    private fun runSync(): Path {
        val repoRoot = resolveRepoRoot()
        val lockPath = repoRoot.resolve("fixture-lock.json")
        check(Files.isRegularFile(lockPath)) {
            "fixture-lock.json not found at $lockPath. Run via `./gradlew test` (which sets " +
                "`interopRepoRoot`) or set `-DinteropRepoRoot=<path>` on the test JVM."
        }
        val lockBytes = Files.readAllBytes(lockPath)
        val lock = parseLock(lockBytes)

        val cacheDir = repoRoot.resolve(".cache").resolve("interop-fixtures").resolve(lock.ref)
        if (cacheIsValid(cacheDir, lock)) {
            println("interop fixtures cache hit: ${lock.source}@${lock.ref}")
            return cacheDir
        }

        println("fetching interop fixtures from ${lock.source}@${lock.ref}...")
        Files.createDirectories(cacheDir)

        val manifestBytes = fetchBytes("${rawBaseUrl(lock)}/manifest.json")
        val manifest = parseAndValidateManifest(manifestBytes, lock)
        Files.write(cacheDir.resolve("manifest.json"), manifestBytes)

        for ((name, entry) in manifest.files) {
            val bytes = fetchBytes("${rawBaseUrl(lock)}/$name")
            val actual = sha256Hex(bytes)
            check(actual == entry.sha256) {
                "$name sha256 mismatch — expected ${entry.sha256}, got $actual"
            }
            val dest = cacheDir.resolve(name)
            Files.createDirectories(dest.parent)
            Files.write(dest, bytes)
            println("  $name (${bytes.size} bytes, sha256 ok)")
        }

        // Marker written last so an interrupted run never produces a "valid" cache.
        Files.writeString(cacheDir.resolve(".sha"), lock.ref)
        println("interop fixtures synced: $cacheDir")
        return cacheDir
    }

    /**
     * Single source of truth for validating fixture bytes against the lockfile. Used both
     * on cold-fetch and warm-cache paths so a stale cache cannot pass weaker checks than a
     * fresh download.
     */
    private fun parseAndValidateManifest(bytes: ByteArray, lock: FixtureLock): FixtureManifest {
        val actualSha = sha256Hex(bytes)
        check(actualSha == lock.manifestSha256) {
            "manifest sha256 mismatch — expected ${lock.manifestSha256}, got $actualSha. " +
                "Either fixture-lock.json is stale or upstream history was rewritten."
        }
        val manifest = try {
            InteropFixtures.json.decodeFromString(FixtureManifest.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            error("manifest.json failed to parse: ${e.message}")
        }
        check(manifest.schemaVersion == InteropFixtures.SCHEMA_VERSION) {
            "unsupported manifest schemaVersion ${manifest.schemaVersion}; this client knows v${InteropFixtures.SCHEMA_VERSION}"
        }
        check(manifest.source == lock.source) {
            "manifest source ${manifest.source} disagrees with lockfile source ${lock.source}"
        }
        for ((name, entry) in manifest.files) {
            check(FIXTURE_FILE_RE.matches(name)) { "manifest contains invalid file name: $name" }
            check(name.split("/").none { it == "." || it == ".." }) {
                "manifest contains path-traversal file name: $name"
            }
            check(name !in RESERVED_FILENAMES) { "manifest references reserved file name: $name" }
            check(SHA256_RE.matches(entry.sha256)) { "manifest entry for $name has invalid sha256" }
        }
        return manifest
    }

    private fun parseLock(bytes: ByteArray): FixtureLock {
        val lock = try {
            InteropFixtures.json.decodeFromString(FixtureLock.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            error("fixture-lock.json failed to parse: ${e.message}")
        }
        // Pinned exactly. The regex-only check was too permissive — there is no scenario
        // where this repo's fixture-lock should ever point at anything other than
        // d4rken-org/octi, and a typo there silently exercises the wrong contract.
        check(lock.source == EXPECTED_SOURCE) {
            "fixture-lock.json source must be \"$EXPECTED_SOURCE\", got: ${lock.source}"
        }
        check(SHA40_RE.matches(lock.ref)) {
            "fixture-lock.json ref must be a 40-character commit SHA (no tags or branches), got: ${lock.ref}"
        }
        check(SHA256_RE.matches(lock.manifestSha256)) {
            "fixture-lock.json manifest_sha256 must be 64 lowercase hex chars"
        }
        return lock
    }

    private fun cacheIsValid(cacheDir: Path, lock: FixtureLock): Boolean {
        val markerPath = cacheDir.resolve(".sha")
        if (!Files.isRegularFile(markerPath)) return false
        if (Files.readString(markerPath).trim() != lock.ref) return false

        val manifestPath = cacheDir.resolve("manifest.json")
        if (!Files.isRegularFile(manifestPath)) return false
        val manifestBytes = Files.readAllBytes(manifestPath)

        val manifest = try {
            parseAndValidateManifest(manifestBytes, lock)
        } catch (_: IllegalStateException) {
            return false
        }

        for ((name, entry) in manifest.files) {
            val filePath = cacheDir.resolve(name)
            if (!Files.isRegularFile(filePath)) return false
            if (sha256Hex(Files.readAllBytes(filePath)) != entry.sha256) return false
        }
        return true
    }

    private fun rawBaseUrl(lock: FixtureLock): String =
        "https://raw.githubusercontent.com/${lock.source}/${lock.ref}/sync-core/src/test/resources/interop"

    private fun fetchBytes(url: String): ByteArray {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(FETCH_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(FETCH_TIMEOUT_MS))
            .GET()
            .build()

        var lastError: Throwable? = null
        repeat(FETCH_RETRIES + 1) { attempt ->
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                val status = response.statusCode()
                when {
                    status in 200..299 -> return response.body()
                    // 4xx is deterministic (bad ref / typo'd path / private repo). Don't burn
                    // retries — surface the real cause.
                    status in 400..499 ->
                        throw IllegalStateException("GET $url → HTTP $status (4xx, not retried)")
                    // 5xx and anything else non-2xx: transient class. IOException makes the
                    // retry catch below pick it up; surface as ISE after the last attempt.
                    else -> throw IOException("GET $url → HTTP $status")
                }
            } catch (e: InterruptedException) {
                // Restore interrupt status, don't retry — test runner is tearing down.
                Thread.currentThread().interrupt()
                throw e
            } catch (e: HttpTimeoutException) {
                lastError = e
                if (attempt < FETCH_RETRIES) println("  fetch timed out; retrying...")
            } catch (e: IOException) {
                lastError = e
                if (attempt < FETCH_RETRIES) println("  fetch IO error (${e.message}); retrying...")
            }
            // Any other Throwable (incl. our IllegalStateException for 4xx) propagates.
        }
        throw IllegalStateException(
            "GET $url failed after ${FETCH_RETRIES + 1} attempts: ${lastError?.message}",
            lastError,
        )
    }

    private fun resolveRepoRoot(): Path {
        // Gradle's `tasks.test { systemProperty("interopRepoRoot", projectDir.absolutePath) }`
        // is the canonical source. user.dir falls in line for a single-module project but
        // explicit is safer across IDE / composite-build invocations.
        System.getProperty("interopRepoRoot")?.let { return Path.of(it).toAbsolutePath() }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) append("%02x".format(b))
        }
    }
}
