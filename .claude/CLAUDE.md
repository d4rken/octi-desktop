# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Octi Desktop

Desktop companion for [Octi](https://github.com/d4rken-org/octi) — Compose Multiplatform JVM client that talks to [Octi Server](https://github.com/d4rken-org/octi-server) over the same end-to-end-encrypted protocol the Android app uses.

- **Package**: `eu.darken.octi.desktop`
- **Architecture**: Compose Multiplatform desktop (JetBrains 1.7) on Kotlin 2.3 / JDK 21
- **Independent Gradle build** — does NOT depend on `app-main`. Wire types are copied; drift held back by behavior fixtures.
- **Remote**: `https://github.com/d4rken/octi-desktop` (moves to `d4rken-org` after first stable release)

## Rules

- [Architecture](rules/architecture.md) — Manual DI graph, Compose Desktop, manual nav, copied-wire-types policy
- [Build Commands](rules/build-commands.md) — Gradle: `run`, `createDistributable`, `test`
- [Testing](rules/testing.md) — JUnit 5 + Kotest + MockK + ktor-server-test-host
- [Code Style](rules/code-style.md) — Kotlin conventions, logging, kotlinx.serialization
- [Debug RPC](rules/debug-rpc.md) — Loopback HTTP endpoint for orchestrating the running app
- [Commit Guidelines](rules/commit-guidelines.md) — Commit message format and examples
