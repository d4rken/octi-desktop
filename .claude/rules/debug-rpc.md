# Debug RPC

A loopback HTTP endpoint that lets a caller (curl, an MCP shim, an end-to-end test) inspect the running app's state and invoke named UI actions — analogous to DebugBadger for Android.

**Off by default.** Enable per-launch with `--enable-debug-rpc`.

## When to use

- You want to drive the desktop app from a script or another agent (an MCP shim equivalent to DebugBadger).
- You're debugging a UI flow and want to inspect graph state without breakpoints.
- You're writing end-to-end tests and need a way to set up scenarios programmatically.

## When NOT to use

- For production telemetry — this is a developer affordance, not an observability layer.
- For automation that the user hasn't opted into — the flag is per-launch by design.

## Starting the server

```bash
# OS-assigned port:
./Octi --enable-debug-rpc

# Pinned port:
./Octi --enable-debug-rpc --debug-rpc-port 53123
```

The startup banner is emitted at INFO level on the first successful bind:

```
DEBUG_RPC url=http://127.0.0.1:53123 token=lRFgQlfmzESDX7RqEOQguwilfjuAkAfb
```

Grab the URL and token from stderr. The token is regenerated each launch.

## Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/dev/health` | No | `{ ok: true, version }` |
| GET | `/dev/state` | Token | Graph state snapshot |
| GET | `/dev/actions` | Token | Registered actions with metadata |
| POST | `/dev/action/{name}` | Token | Invoke action with JSON body |
| GET | `/dev/screen.png` | Token | PNG of primary monitor (503 if unavailable) |

All authenticated calls require header `X-Debug-Token: <value>`.

## State payload

`GET /dev/state` returns:

```json
{
  "version": "0.1.0-dev",
  "deviceId": "...",
  "screen": "dashboard",              // or "linking", "settings", "device:<id>", "files:<id>", "clipboard"
  "activeClientPresent": true,         // credentials configured (NOT == "connected")
  "webSocketState": "Connected",       // Idle | Connecting | Connected | Reconnecting | PollingFallback
  "deviceListLoadState": "Ok",         // Initial | Loading | Ok | Idle | Error
  "deviceCount": 2,
  "knownDevices": [
    {"deviceId": "...", "label": "blasphemy", "platform": "desktop-linux", "lastSeen": "..."},
    {"deviceId": "...", "label": "Pixel 8", "platform": "android", "lastSeen": "..."}
  ],
  "lastMetaWriteSuccessAt": "...",     // null until our first /v1/module/.../meta write succeeds
  "lastWsEventAt": null                // null until the first inbound WS event arrives
}
```

Important: `activeClientPresent == true` means we have credentials and a client object — it does NOT mean the WebSocket is currently connected. Check `webSocketState` for that.

## Built-in actions

```
dashboard.refresh            — kick the device-list poll loop now
dashboard.openDevice         — navigate to a device's detail screen (validates deviceId)
navigation.go                — jump to "linking" | "dashboard" | "clipboard" | "settings"
linking.submit               — submit a share code as if pasted on the Linking screen
```

Discover at runtime via `GET /dev/actions` — each entry includes `description`, `params`, and `example`.

## Adding a new action

Two flavors. **Pick `registerUiAction` for anything that mutates Compose state or `Navigator`**; otherwise `register`.

### Graph-level (always available)

Add to `AppGraph.registerDebugActions()`. Pattern:

```kotlin
debugActions.registerUiAction(
    DebugActionRegistry.Metadata(
        name = "files.share",
        description = "Trigger a file share to a specific peer.",
        params = mapOf(
            "deviceId" to "string — recipient device id",
            "path" to "string — absolute path to file on the host",
        ),
        example = """{"deviceId":"abc-123","path":"/tmp/test.txt"}""",
    ),
) { params ->
    val deviceId = params["deviceId"]?.jsonPrimitive?.content ?: error("missing deviceId")
    val path = params["path"]?.jsonPrimitive?.content ?: error("missing path")
    fileShareRepo.share(deviceId, java.io.File(path))
    buildJsonObject { put("queued", JsonPrimitive(true)) }
}
```

- The `Metadata.name` should be `<feature>.<verb>` (dots, not slashes).
- Throw to surface a structured `500 action_failed` to the caller — the message becomes the error string. Use a recognizable prefix (e.g. `device_not_found:`) so callers can match on it.
- The handler is suspend; do `withContext(Dispatchers.IO)` for blocking work.

### Screen-local (only registered while a screen is mounted)

Use `DisposableEffect` in a composable:

```kotlin
val graph = LocalAppGraph.current
DisposableEffect(Unit) {
    val handle = graph.debugActions.registerUiAction(
        DebugActionRegistry.Metadata(
            name = "files.upload-current-selection",
            description = "Upload whatever is currently selected on the files screen.",
        ),
    ) {
        viewModel.uploadSelection()
        JsonPrimitive("ok")
    }
    onDispose { handle.unregister() }
}
```

**Token-based ownership matters here**: if Screen A unmounts and Screen B remounts the same name before A's `onDispose` runs, A's stale unregister is a no-op. Never bypass this with raw map mutation.

## Threading

- `registerUiAction(...)` — handler runs on `Dispatchers.Swing` (AWT EDT). Use for Compose state, `Navigator`, or any `MutableStateFlow` mutation.
- `register(...)` — handler runs on the request coroutine context. Use for HTTP / file I/O / pure data shuffling.

Mixing models inside one handler: it's fine to start UI-side (`registerUiAction`) and `withContext(Dispatchers.IO)` for the network leg. Just dispatch off the EDT before doing blocking work.

## Failure modes & status codes

| Failure | HTTP | `error` code |
|---------|------|--------------|
| Token missing or wrong | 401 | `unauthorized` |
| Action name unknown | 404 | `action_not_registered` |
| Body > 64 KiB | 413 | `payload_too_large` |
| Body not a JSON object | 400 | `invalid_params` |
| Action throws | 500 | `action_failed` (message = exception message) |
| Action exceeds 30s | 504 | `action_timeout` |
| Screenshot unavailable (headless, AWT failure) | 503 | `screenshot_unavailable` |

Every error response is `{ "error": "<code>", "message": "<human-readable>" }`.

## Security model

- Bound to `127.0.0.1` only — never `0.0.0.0`. Hardcoded in `DebugRpcServer.kt`.
- One-time random 24-byte token, URL-safe base64, regenerated each launch.
- No CORS — the custom `X-Debug-Token` header forces a preflight that no browser drive-by from another origin can satisfy.
- Per-action `Mutex` serializes concurrent invocations of the same action (prevents racing navigation / form state).
- Body size capped at 64 KiB before the JSON parser sees it.

Threat model assumes the attacker doesn't already have local code execution on the same user account — if they do, they can read the keystore directly, so the RPC isn't the weak link. Loopback + token is appropriate for a developer tool, not a hardening boundary.

## Files

```
debug/rpc/
├── DebugRpcConfig.kt         # CLI flag parser
├── DebugActionRegistry.kt    # named-verb registry with token ownership + mutex + timeout
├── DebugStateProvider.kt     # snapshots graph flows into JSON (interface: DebugStateSource)
├── DebugScreenshot.kt        # AWT Robot wrapper (Result<ByteArray>)
└── DebugRpcServer.kt         # Ktor CIO server + route registration + token auth + error mapping
```

Tests live in `src/test/kotlin/.../debug/rpc/`:

- `DebugRpcConfigTest` — flag parsing edge cases (missing value, non-numeric, range, duplicates).
- `DebugActionRegistryTest` — registration ownership, mutex serialization, timeout, list ordering.
- `DebugRpcServerTest` — Ktor route tests via `testApplication { ... }` covering auth, body limits, error mapping, action lifecycle.

## Out of scope

- WebSocket / SSE on `/dev/*` — actions are synchronous request/response only.
- Recording / replaying sessions.
- Multi-window — assumes one main window.
- The MCP shim itself — that's a separate concern (TypeScript, separate repo). The HTTP contract here is the stable surface to wrap.
