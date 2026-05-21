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
- WebSocket push: linked peer opens `/v1/ws` and receives `ModuleChanged` for the other peer's write

If `SMOKE_SERVER_URL` is unset, tests skip cleanly via JUnit assumptions.

## What's intentionally NOT here (yet)

- Compose UI tests (`runComposeUiTest`) — not wired in this project yet.

## Cross-repo wire-format fixtures (multi-source)

Desktop is a cross-repo fixture consumer for both producers in the network:

| Source | Fixtures | Tests |
|---|---|---|
| `d4rken-org/octi` (sync-core) | Tink + streaming AEAD vectors | `InteropFixtureVerifyTest` |
| `d4rken-org/octi-web` (published/) | MetaInfo / ClipboardInfo / FileShareInfo JSON | `WebMetaInteropTest` + `WebClipboardInteropTest` + `WebFilesInteropTest` |

`fixture-lock.json` at this repo root uses **schema v2** (multi-source):

```json
{
  "schemaVersion": 2,
  "sources": {
    "d4rken-org/octi":      { "ref": "<sha40>", "manifest_sha256": "<sha256>" },
    "d4rken-org/octi-web":  { "ref": "<sha40>", "manifest_sha256": "<sha256>" }
  }
}
```

`InteropFixtureSync.ensureSynced("d4rken-org/...")` fetches + verifies on first `@BeforeAll`;
cache lives at `.cache/interop-fixtures/<owner>/<repo>/<sha>/` (gitignored). The
double-checked-locking singleton means multiple test classes hitting different sources sync
each source once per JVM.

The parser also accepts the **legacy v1 flat shape** (`{ source, ref, manifest_sha256 }`)
during the migration window — a future hand-edit revert to v1 still parses.

To bump a pin, change `fixture-lock.json#sources[<source>].ref` to a newer commit SHA on
that producer, recompute `manifest_sha256` via `sha256sum` on the manifest at that SHA, and
commit both. `./gradlew test` then re-fetches and re-verifies.

### `INTEROP_FIXTURE_OVERRIDES` (CI gate)

Cross-repo gating workflows (octi-web's `cross-repo-verify.yml` and friends) set
`INTEROP_FIXTURE_OVERRIDES='{"<owner>/<repo>":"<head-sha>"}'` to point one of the locked
sources at a PR's HEAD without rewriting the lockfile. The override drops `manifestSha256`
as a trust anchor (no committed sha can pin an arbitrary upstream commit) — per-file sha256s
in the freshly-fetched manifest stay as the anchor. The env var is declared as a Gradle test
input so an overridden run can't be UP-TO-DATE skipped.

If you also touch `StreamingPayloadCipher` or `PayloadEncryption`, expect `InteropFixtureVerifyTest`
to catch the wire-compat break before the smoke suite does. If you touch one of the modules
listed above on web, expect `Web*InteropTest` to flag the change here at the consumer side.

### Upstream gating (this repo's CI)

`.github/workflows/cross-repo-verify.yml` runs on every PR. On PRs that touch the
allowlisted wire-format paths (`protocol/modules/`, `protocol/sync/`,
`protocol/serialization/`, `protocol/module/`,
`src/test/kotlin/.../interop/published/`, `src/test/resources/interop/published/`,
and the workflow itself), it checks out app-main and octi-web at their default
branches and runs their consumer suites against this PR's HEAD using the
`INTEROP_FIXTURE_OVERRIDES` env var the consumer sync code already accepts.
Sister gates: app-main's `cross-repo-verify.yml` (A3) and octi-web's (B4) fire
the same shape from their own directions.

A wire-incompatible serializer change is blocked at the octi-desktop PR, not
discovered later when a consumer happens to bump its pin. PRs that don't touch
the allowlist still run the workflow but echo "no wire-format-relevant paths
changed; consumer verify will be skipped." and exit 0 — required-check status
reports green without leaving the check pending.

Fork PR limitation: cross-repo `actions/checkout` of `head.sha` only works for
same-repo branches; fork PRs get a clean failure from the consumer's
`raw.githubusercontent.com` fetch (returns 404 against the upstream's path).
Same constraint as the sister workflows.

### Publishing canonical fixtures (Phase C1 onward)

Desktop is also a **producer**. Canonical payloads desktop emits for `meta`,
`clipboard`, and `files` are committed under
`src/test/resources/interop/published/` for app-main and octi-web to consume.

Owners:
- `InteropFixtureGenerator` — pure builder + canonical typed inputs per vector.
- `InteropFixtureSelfVerifyTest` — always-on round-trip gate. Re-runs the
  generator on every `./gradlew test` and asserts the committed JSON files
  match byte-for-byte.
- `InteropFixtureGeneratorTest` — `@EnabledIfSystemProperty`-gated regenerator.
  Disabled on plain `./gradlew test`; the Gradle task below flips the gate
  and writes the freshly-built bytes to disk.

Regenerate via:

```bash
./gradlew generateDesktopFixtures
```

The task scopes to the single regenerator test class and forces a re-run
(`outputs.upToDateWhen { false }`). If a canonical input in
`InteropFixtureGenerator` changes (or the serializer's output drifts), the
self-verify test fails on the next `./gradlew test` — regenerate and commit
in the same PR. Don't commit hand-edited fixture JSON.

Power and connectivity modules are deliberately omitted from the producer
fixture set: desktop reads them (UI tiles) but doesn't emit them. Add a
vector here only if/when desktop gains a writer for that module.

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
