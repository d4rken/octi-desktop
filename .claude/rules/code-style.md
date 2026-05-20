# Code Style

## General

- Package by feature, not by layer.
- Prefer adding to existing files unless creating a new logical component.
- Don't comment what well-named code already says. Comments explain *why*, not *what*.
- Trailing commas on multi-line parameter lists.

## Logging

```kotlin
import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag

private val TAG = logTag("Module", "Power", "Sync")
// Produces: "🐙:Module:Power:Sync"

log(TAG) { "Processing $item" }            // DEBUG (default)
log(TAG, INFO) { "Scan complete" }          // INFO
log(TAG, WARN) { "Unexpected state" }       // WARN
log(TAG, ERROR, e) { "Failed: ${e.message}" }
```

## Serialization

- All wire types use kotlinx.serialization. Moshi is NOT used here.
- `@file:UseSerializers(...)` for project-wide custom types (ByteString, Instant, etc.).
- Inject the project `Json` from `protocol/serialization/Serialization.kt`. Don't create ad-hoc `Json {}` instances in production code.
- **`@SerialName` is load-bearing** when a Kotlin property name doesn't match the wire field — see [Architecture / Wire types](architecture.md#wire-types--copied-not-shared) for the known typos.

## Flow patterns

- `MutableStateFlow<T>` for observable single-value state. `StateFlow<T>` for the read-only view.
- `MutableSharedFlow<T>` for event streams (no replay, small bounded buffer).
- `flatMapLatest` to switch between sub-flows when an upstream changes (e.g. `activeConnectors` → per-connector polling loop).
- `launchIn(graph.appScope)` to attach a flow to the app lifetime.

## Coroutine scopes

- `AppGraph.appScope` — application-lifetime scope. Use for daemons / polling loops / WS sessions.
- `Dispatchers.IO` — file I/O, network calls.
- `Dispatchers.Swing` — Compose UI mutations (AWT EDT). Required when touching `MutableStateFlow<Screen>` in `Navigator` from a non-UI context.
- `Dispatchers.Default` — CPU-bound work.

## ViewModel-likes

There's no Android `ViewModel` class on desktop. Per-screen "view models" are plain classes that:

- Take `AppGraph` (or a narrower dependency) in the constructor
- Expose state as `StateFlow<T>` via `MutableStateFlow`
- Launch work in `graph.appScope` (so navigation away doesn't cancel in-flight requests mid-rollback)

See `LinkingViewModel.kt` for the canonical pattern.

## DataStore-equivalent

Desktop doesn't use Android's `DataStore`. `Settings.kt` is a plain file-backed JSON struct with atomic temp+fsync+rename writes and a `schemaVersion` field for migrations.

## Manual `@Suppress` placement

Place `@Suppress("UnnecessaryAbstractClass")` etc. as close as possible to the affected declaration, not at file scope.

## File headers

No copyright headers in source files. GPLv3 license sits at `app-desktop/LICENSE` and is referenced by the README.
