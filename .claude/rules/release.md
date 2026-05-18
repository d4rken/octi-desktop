# Releasing

Same shape as `sync-server` releases — see `sync-server/.claude/rules/release.md` for the
canonical commentary. Differences specific to octi-desktop are below.

## Flow at a glance

```
release-prepare.yml (workflow_dispatch)
  └─ Job 1: compute-and-validate  (always)
       reads gradle.properties version=, computes next, checks tag collision, writes summary
  └─ Job 2: push-and-dispatch  (only when dry_run=false)
       mints d4rken-org-releaser App token, bumps gradle.properties, commits "Release: vX.Y.Z",
       tags, atomic push
           │
           └─► release-tag.yml fires automatically from the App-token push
                 └─ validate-tag                  regex + gradle.properties consistency
                 └─ build-linux                   ubuntu-22.04 → .deb + .rpm + .AppImage + .tar.gz
                 └─ build-macos-arm               macos-14    → Octi-X.Y.Z-macos-arm64.dmg
                 └─ build-macos-intel             macos-13    → Octi-X.Y.Z-macos-x64.dmg
                 └─ build-windows                 windows-2022 → Octi-X.Y.Z.msi
                 └─ release-github                aggregate + checksums.txt + softprops/action-gh-release
```

Each per-OS build runs a `--version` smoke step against the built binary before uploading — proves
the bundled JRE + jlink modules + native libs load cleanly. CI fails loud if the artifact won't
launch.

## Version source of truth

`gradle.properties` — the single `version=X.Y.Z[-rcN|-betaN]` line. `build.gradle.kts` reads
`project.version` from it. A `generateBuildConfig` task writes a `BuildConfig.kt` with the
version baked in; `DeviceMetadataProvider.APP_VERSION` reads from it.

The release workflow bumps only this one line.

## jpackage prerelease quirk

`packageVersion` rejects non-numeric versions, so the build strips the `-rc1` / `-beta1` /
`-dev` suffix before feeding to jpackage:

```kotlin
val rawVersion = project.version.toString()
val numericVersion = rawVersion.replace(Regex("-(rc|beta|dev)\\d*$"), "")
```

This means `1.0.0-beta1`, `1.0.0-rc1`, and `1.0.0` all produce `packageVersion=1.0.0`. The
filename carries the full version (`Octi-1.0.0-beta1.dmg`) so GitHub Releases stays
unambiguous, but the OS-level package version inside the installer is the same `1.0.0`.

Consequence: switching between prereleases of the same X.Y.Z (e.g. beta1 → rc1) requires
uninstall + reinstall on Windows MSI. Stable → stable upgrades work normally.

## DMG MAJOR > 0 requirement

Compose Desktop's DMG packaging rejects `0.X.Y` versions ("MAJOR must be > 0"). The desktop
project starts at `1.0.0` for this reason. Future bumps stay above 1.0.0.

## Release-tag.yml is push-only

Unlike sync-server, the desktop release-tag workflow does NOT have `workflow_dispatch`. The
`github.ref_name` on a manual dispatch from `main` is `"main"`, which fails the tag regex.
Dry runs happen exclusively via `release-prepare.yml`'s Job 1 — that validates everything
without producing any artifacts.

## PR-title-based changelog

`softprops/action-gh-release` is called with `generate_release_notes: true`. GitHub uses the
titles of PRs merged since the last tag. Write PR titles like you'd want them to appear in a
changelog:

- ✅ "Add system tray icon"
- ✅ "Fix WebSocket reconnect after sleep on macOS"
- ❌ "tray"
- ❌ "fix bug"

Bad titles end up in the release notes verbatim. Reviewers should fix them before merge.

## Prerequisites (one-time setup)

Before any non-dry-run release works:

1. **Install `d4rken-org-releaser` GitHub App on this repo**. Same App as octi-server / octi.
2. **Make org-level secrets `RELEASE_APP_CLIENT_ID` + `RELEASE_APP_PRIVATE_KEY` accessible to
   this repo** (already set on `d4rken-org` org; the repo just needs to live in / be granted
   access to the org).
3. **Add the App as a bypass actor** in any branch / tag protection rulesets covering `main`
   and `v*` tags. `GITHUB_TOKEN` cannot be a bypass actor — only installed Apps can.

The `d4rken-org-releaser` App is currently installed on `d4rken-org/octi-desktop`. If the
repo ever moves again, the App install + bypass-actor entries follow the new location.

## First release caveat

`generate_release_notes` produces an empty changelog when there's no prior tag. For v1.0.0,
either hand-edit the GitHub Release body to something useful, or accept the empty changelog.
Subsequent releases auto-populate from PR titles.

## Recovery procedures

**Build failure after tag push** (one matrix job failed, tag exists, gradle.properties bumped):
1. Fix the underlying issue on `main`.
2. Re-run the failed `release-tag.yml` job from the Actions page — `release-github` doesn't
   re-run individual matrix jobs without re-running the whole workflow, so the simplest path
   is to bump and re-tag with a new patch via `release-prepare.yml`.

**Rollback** (tag pushed but you want to undo before the release is published):
1. Delete the remote tag: `git push origin --delete vX.Y.Z`
2. If `release-github` already published: `gh release delete vX.Y.Z --yes`
3. Revert the bump commit on `main`: `git revert <sha>` + push.
4. Re-cut from the correct state via `release-prepare.yml`.

## Signing — still deferred

All artifacts are **unsigned**. macOS Gatekeeper + Windows SmartScreen will warn on first
launch. The README documents the bypass. Notarization is its own project (Apple Developer
membership + Windows Authenticode cert + secret wiring).
