# Build Commands

## Building

```bash
./gradlew run                  # run from source (Compose Multiplatform application plugin)
./gradlew createDistributable  # produces a runnable image under build/compose/binaries/main/app/Octi
./gradlew test                 # unit + integration tests
./gradlew check                # tests + verification
```

**Note**: `installDist` is NOT a thing here — that's the sync-server's Ktor plugin. Compose Multiplatform uses `createDistributable`.

## Running the built binary

```bash
./build/compose/binaries/main/app/Octi/bin/Octi
```

Bundled JRE; self-contained. Add CLI flags after the executable:

```bash
./build/compose/binaries/main/app/Octi/bin/Octi --enable-debug-rpc --debug-rpc-port 53123
```

See [debug-rpc.md](debug-rpc.md) for the orchestration endpoint.

## Native packaging (Phase H — not wired yet)

```bash
./gradlew packageDmg           # macOS
./gradlew packageMsi           # Windows
./gradlew packageDeb           # Linux .deb
./gradlew packageAppImage      # Linux AppImage
```

These tasks exist in the Gradle Compose plugin but signing/notarization isn't configured yet — they'll produce unsigned artifacts that trigger Gatekeeper/SmartScreen warnings on macOS/Windows.

## JDK

JDK 21 (toolchain enforced via `jvmToolchain(21)`). Older JDKs will fail at configure time.

## Context Management

When running gradle builds or tests, use the Task tool with `devtools:build-runner` to keep verbose output out of the main conversation. Run gradle directly in the main context only when the user explicitly requests full output.
