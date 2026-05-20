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
- Lazy properties for components that depend on `activeConnectors` (e.g. `webSocketClient`, `metaWriter`, `fileShareRepo`) — wired only on first access, so the graph constructs even when the connector list is still empty (pre-link state).
- The graph also owns the `DebugActionRegistry` — see [debug-rpc.md](debug-rpc.md).

## Sync state machine

Two top-level states, both modelled explicitly on the graph:

- **Pre-link**: `activeConnectors` is empty; navigator starts at `Screen.Linking`.
- **Post-link**: `activeConnectors` has at least one entry; navigator starts at `Screen.Dashboard`.

Transitions:

- `AppGraph.onLinked(connectorId, credentials)` is called by the Link/Create flow on success. The two-store commit (keystore + `SettingsData.connectors`) is done by `LinkController` BEFORE `onLinked` runs; `onLinked` is purely in-memory (build the `OctiServerConnector`, append to `_activeConnectors`, navigate). See [linking-flow](#linking-flow) below.
- `AppGraph.unlink(connectorId)` issues server `DELETE /v1/devices/{self}` first, then clears the matching keystore entry AND the `SettingsData.connectors` entry. Network failure keeps local state untouched so the user can retry.

Each connector owns its `OctiServerHttpClient`; relink rebuilds rather than mutating in place (avoids stale auth state on the Ktor connection pool).

### Per-connector storage model

Multi-connector-ready shapes are baked in from the start (single OctiServer connector today; a future GDrive connector slots in without migrations):

- **`Settings.connectors: Map<String, ConnectorConfig>`** — discovery index keyed by `ConnectorId.idString`. The structured `ConnectorId` lives in `ConnectorConfig.connectorId`; the map key is opaque (don't parse it — `-` separator collides with hyphens in hostnames/UUIDs).
- **Keystore key** — `"${DesktopIdentity.credentialsKeyPrefix}.${connectorId.idString}"`. The channel-aware prefix prevents canary↔stable cross-channel reads; the full `idString` suffix prevents UUID collisions between custom OctiServer instances.
- **`DeviceListCache`** — `{schemaVersion, perConnector: Map<idString, List<Device>>}`. Load filters by `Settings.connectors` so a stale cache can't resurrect ghost devices after unlink.
- **`OctiServerConnector`** — bundles `(identifier, credentials, client)` so consumers never split-fetch the http client and credentials separately. Lifetime tied to the credentials.

### Linking flow

`LinkController.link` / `createAccount` orchestrate the two-store commit:

1. Validate input (local-only).
2. Server consume (`register` / `createAccount`).
3. Save credentials to keystore. On failure → rollback DELETE on server.
4. Add `ConnectorConfig` entry to `Settings.connectors`. On failure → best-effort delete keystore entry + rollback DELETE on server, surface as `SettingsPersistFailedRolledBack`.
5. Return `Success(connectorId, credentials)`. The ViewModel passes both to `AppGraph.onLinked(...)`.

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

`OctiServerWebSocketClient` owns one supervisory coroutine per active connector, all bound to `AppGraph.appScope`. Per-connector state is exposed via `statesByConnector: StateFlow<Map<ConnectorId, ConnectionState>>`; the back-compat aggregate `state` reflects the primary connector. Set deltas on `activeConnectors` create/cancel per-connector loops. State machine: `Idle → Connecting → Connected → (Reconnecting | PollingFallback)`. Backoff is jittered exponential `min(60s, 2^n * 1s) ± 25%`.

## File sharing — GCM-SIV only

Tink's streaming AEAD (`StreamingPayloadCipher`) is GCM-SIV-only on both `tink-java` and `tink-android`. Legacy `AES256_SIV` accounts cannot use file sharing from desktop. Settings shows the keyset type so users can tell which mode they're on.

## Cryptography providers

- Conscrypt-OpenJDK is the primary JCE provider. Tink Android also uses Conscrypt, so this matches bytes-for-bytes for GCM-SIV.
- BouncyCastle stays in as a fallback for hosts where Conscrypt's native lib can't load.

## What's intentionally NOT here

- Compose Navigation3 (Android-only)
- Hilt (Android-only)
- Power / WiFi / Apps / Connectivity collection on desktop (deferred — needs per-OS CLI parsing)
- Google Drive sync connector — wire shapes (`SettingsData.connectors`, `DeviceListCache.perConnector`, debug RPC `connectors[]`) are multi-connector-ready; needs the second `SyncConnector`-shaped impl and a hub-style registry
- Multi-account UI — the storage model supports it (keystore + `Settings.connectors` are keyed by full `ConnectorId.idString`); the link/unlink flow currently still operates on `primaryConnector` only
- Per-module merging (Android's `latestData()` groups by deviceId × moduleId and picks newest `modifiedAt`) — desktop merges at device level today via `MergedDevice`
- jpackage packaging (Phase H)
