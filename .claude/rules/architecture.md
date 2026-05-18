# Architecture

## Top-level layout

```
app-desktop/
├── build.gradle.kts             # independent build — NOT in app-main/settings.gradle
├── settings.gradle.kts
├── src/main/kotlin/eu/darken/octi/desktop/
│   ├── Main.kt                  # entry point + CLI parse + AppGraph wiring
│   ├── common/                  # log, coroutine, atomic file helpers
│   ├── debug/rpc/               # opt-in loopback HTTP RPC (see rules/debug-rpc.md)
│   ├── di/AppGraph.kt           # manual DI factory; no Hilt on desktop
│   ├── linking/                 # share-code flow, CredentialsStore, LinkController
│   ├── modules/{meta,clipboard,files}/  # local sources + writers
│   ├── protocol/                # COPIED wire types (no KMP extraction)
│   │   ├── encryption/          # tink-java PayloadEncryption + StreamingPayloadCipher
│   │   ├── modules/{meta,clipboard,power,...}/   # @Serializable DTOs
│   │   ├── octiserver/          # OctiServerHttpClient (Ktor), DeviceMetadata headers
│   │   └── serialization/       # Json singleton + custom serializers
│   ├── storage/                 # Settings (JSON), Keystore (JNA), passphrase fallback
│   ├── sync/                    # DeviceListRepo, ModuleReader, WebSocket client, SyncEventBus
│   └── ui/                      # Compose screens (linking, dashboard, files, clipboard, settings)
└── src/test/kotlin/...
```

## Dependency Injection

Manual factory pattern in `di/AppGraph.kt`. No Hilt — Hilt is Android-only.

- `AppGraph.create(passphrasePrompt)` is called once from `Main`.
- The graph is threaded down through Compose via `LocalAppGraph` (a `CompositionLocal`).
- Lazy properties for components that depend on `activeClient` (e.g. `webSocketClient`, `metaWriter`, `fileShareRepo`) — wired only on first access, so the graph constructs even when `activeClient` is still null (pre-link state).
- The graph also owns the `DebugActionRegistry` — see [debug-rpc.md](debug-rpc.md).

## Sync state machine

Two top-level states, both modelled explicitly on the graph:

- **Pre-link**: `activeClient` is null; navigator starts at `Screen.Linking`.
- **Post-link**: `activeClient` is non-null; navigator starts at `Screen.Dashboard`.

Transitions:

- `AppGraph.onLinked()` is called after a successful `POST /v1/account?share=...`. It loads credentials, builds a fresh `OctiServerHttpClient`, swaps `_activeClient.value`, and navigates to `Dashboard`.
- `AppGraph.unlink()` clears credentials locally (does NOT call the server's `DELETE /v1/devices/{self}` — leave that to the Settings screen).

The previous client is `close()`-d on every transition rather than mutating config in place. Avoids stale auth state on the Ktor connection pool.

## Wire types — copied, not shared

Models, serializers, and HTTP DTOs are copied from `app-main`, NOT depended on as a Gradle module. `app-desktop` is JVM-only; `app-main` is Android-specific. Drift is held back by:

1. **Behavior fixtures** — link-code decode, AAD encrypt/decrypt round-trip, blob upload/download round-trip against a live `sync-server`. These are higher-signal than rote field enumeration.
2. **Golden JSON fixtures** — fixed instances of `PowerInfo`, `FileShareInfo`, `LinkingData`, `ModuleCommitRequest` checked into both repos.
3. **`@SerialName` spot checks** on the known-quirky fields (`serverAddress`/`serverAdress`, `currentAvg`/`currenAvg`, `deleteRequests`, `availableOn`, `connectorId`/`accountId`).

When in doubt: read `app-main`'s file before writing the desktop copy. Don't trust your memory.

## Navigation

`Screen.kt` is a sealed class. `Navigator` exposes `current: StateFlow<Screen>` and a `navigateTo(screen, clearStack)`. Single-entry back stack.

Compose-Navigation3 is Android-only — we use a plain `MutableStateFlow<Screen>` and a `ScreenRouter` `when` block.

## WebSocket lifecycle

`OctiServerWebSocketClient` owns one supervisory coroutine bound to `AppGraph.appScope`. It runs only while `AppGraph.activeClient` is non-null; `flatMapLatest` over the active client transitions tears down the prior loop cleanly. State machine: `Idle → Connecting → Connected → (Reconnecting | PollingFallback)`. Backoff is jittered exponential `min(60s, 2^n * 1s) ± 25%`.

## File sharing — GCM-SIV only

Tink's streaming AEAD (`StreamingPayloadCipher`) is GCM-SIV-only on both `tink-java` and `tink-android`. Legacy `AES256_SIV` accounts cannot use file sharing from desktop. Settings shows the keyset type so users can tell which mode they're on.

## Cryptography providers

- Conscrypt-OpenJDK is the primary JCE provider. Tink Android also uses Conscrypt, so this matches bytes-for-bytes for GCM-SIV.
- BouncyCastle stays in as a fallback for hosts where Conscrypt's native lib can't load.

## What's intentionally NOT here

- Compose Navigation3 (Android-only)
- Hilt (Android-only)
- Power / WiFi / Apps / Connectivity collection on desktop (deferred — needs per-OS CLI parsing)
- Google Drive sync connector (Phase H+)
- Multi-account support (single-account MVP)
- jpackage packaging (Phase H)
