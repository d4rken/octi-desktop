# Testing

## Stack

JUnit 5 + Kotest matchers + MockK + ktor-client-mock + **ktor-server-test-host** (for the debug RPC routes) + kotlinx-coroutines-test.

## Patterns

```kotlin
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExampleTest {

    @Test
    fun `descriptive backticked name`() = runTest {
        val result = doThing()
        result shouldBe expected
    }

    @Test
    fun `error path uses assertThrows`() {
        assertThrows<IllegalArgumentException> { doBadThing() }
    }
}
```

- Backtick names for readability.
- Kotest's `shouldBe` for assertions, `shouldBeInstanceOf<T>` for sealed-case checks.
- `runTest` from kotlinx-coroutines-test for suspend-function tests.

## Ktor routes

For the debug RPC, tests use `testApplication { ... }` from `ktor-server-test-host` and pass the same `debugRpcModule(...)` function the production server uses. See `DebugRpcServerTest.kt` for the pattern. **Don't** spin up a real `embeddedServer` in tests — bind failures on shared CI runners are painful.

## Smoke suite

`src/smokeTest/kotlin/.../__smoke__/` contains Ubuntu server-smoke tests against a real
`sync-server` container. They are excluded from `./gradlew check` and run explicitly via:

```bash
SMOKE_SERVER_URL=http://127.0.0.1:18080 ./gradlew smokeTest
```

CI runs this in `code-checks.yml` against the floating `ghcr.io/d4rken-org/octi-server:canary`
tag — octi-server's rolling main build, republished on every server main push. That means
the desktop smoke fails the day an upstream protocol change lands, without anyone bumping a
pin. The flip side: an unexplained smoke failure may be an upstream regression, not a
desktop one — check octi-server main before assuming the desktop side broke.

Locally, start the server first:

```bash
docker run --rm -p 18080:8080 ghcr.io/d4rken-org/octi-server:canary
```

For a reproducible debugging session against a specific server commit, swap the tag for the
matching `sha-<short>` immutable tag from `ghcr.io/d4rken-org/octi-server`.

The smoke suite covers:

- single-device meta module write/read using the real gzip → encrypt wire shape
- blob session upload → module commit → list → download/decrypt
- two-device share-code interop for meta + blob reads

If `SMOKE_SERVER_URL` is unset, tests skip cleanly via JUnit assumptions.

## What's intentionally NOT here (yet)

- Compose UI tests (`runComposeUiTest`) — not wired in this project yet.

## Cross-platform encryption fixtures

`InteropFixtureVerifyTest` pins desktop's [`PayloadEncryption`] and [`StreamingPayloadCipher`]
against committed Android-produced ciphertext + plaintext + AAD. The fixtures live upstream in
[`d4rken-org/octi`](https://github.com/d4rken-org/octi) (sync-core/src/test/resources/interop/);
`fixture-lock.json` at this repo root pins a 40-char commit SHA + the SHA-256 of that commit's
`manifest.json`. `InteropFixtureSync` fetches and verifies on first `@BeforeAll`; the cache lives
at `.cache/interop-fixtures/<sha>/` (gitignored).

To bump the pin, change `fixture-lock.json#ref` to a newer app-main commit, recompute
`manifest_sha256` via `sha256sum` on the manifest at that SHA, and commit both in the same
change. `./gradlew test` then re-fetches and re-verifies.

If you also touch `StreamingPayloadCipher` or `PayloadEncryption`, expect this test to catch
the wire-compat break before the smoke suite does.

## Behavior fixtures vs. enumeration

Drift between `app-desktop` and `app-main` is held back by **behavior fixtures**, not exhaustive field enumeration. Prefer:

- `LinkingDataTest` (link-code decode round-trip)
- `PayloadEncryptionTest` (AAD encrypt/decrypt for both AES256_SIV and AES256_GCM_SIV)
- `StreamingPayloadCipherTest` (blob round-trip with the same AAD format)

over `assertSerialNameForEveryField`. The behavior fixtures catch real interop breakage; the field-level checks mostly catch typos that the behavior tests would also catch.

## Running a single test

```bash
./gradlew test --tests "*DebugRpcConfigTest"
./gradlew test --tests "*DebugRpcConfigTest.port zero is rejected"
```

## Context Management

Test runs go through `devtools:build-runner` for context hygiene. Run directly only when the user explicitly asks for full output.
