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
- Cross-platform encryption fixtures (Android encrypts, desktop decrypts) — Phase C work; today's coverage is per-side unit tests plus server smoke.

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
