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
                 └─ build (calls build-installers.yml, channel=stable)
                       └─ build-linux             ubuntu-22.04 → .deb + .rpm + .AppImage + .tar.gz
                       └─ build-macos-arm         macos-14    → Octi-X.Y.Z-macos-arm64.dmg
                       └─ build-macos-intel       macos-15-intel → Octi-X.Y.Z-macos-x64.dmg
                       └─ build-windows           windows-2022 → Octi-X.Y.Z.msi
                 └─ release-github                aggregate + checksums.txt + softprops/action-gh-release
```

The per-OS jpackage matrix lives in the reusable `build-installers.yml` (`on: workflow_call`)
so the same matrix is reused unchanged by `release-nightly.yml`. Each per-OS build runs a
`--version` smoke step against the built binary before uploading — proves the bundled JRE +
jlink modules + native libs load cleanly. CI fails loud if the artifact won't launch.

## Version source of truth

`gradle.properties` — the single `version=X.Y.Z[-rcN|-betaN]` line. `build.gradle.kts` reads
`project.version` from it. A `generateBuildConfig` task writes a `BuildConfig.kt` with the
version baked in; `DeviceMetadataProvider.APP_VERSION` reads from it.

The release workflow bumps only this one line.

## jpackage prerelease quirk

`packageVersion` rejects non-numeric versions, so the build strips the prerelease suffix
before feeding to jpackage:

```kotlin
val rawVersion = project.version.toString()
val numericVersion: String = rawVersion.replace(
    Regex("-(rc\\d+|beta\\d+|dev\\d*|nightly\\.[0-9a-zA-Z]+)$"),
    "",
)
```

This means `1.0.0-beta1`, `1.0.0-rc1`, `1.0.0-nightly.abc12345`, and `1.0.0` all produce
`packageVersion=1.0.0`. The filename carries the full version (`Octi-1.0.0-beta1.dmg`) so
GitHub Releases stays unambiguous, but the OS-level package version inside the installer
is the same `1.0.0`.

Consequence: switching between prereleases of the same X.Y.Z (e.g. beta1 → rc1) requires
uninstall + reinstall on Windows MSI. Stable → stable upgrades work normally.

Nightly avoids this problem entirely by using a different `upgradeUuid` / `packageName` /
`bundleID` per channel — nightly installs as a distinct "Octi Nightly" product, not as an
upgrade over stable. See the Nightly section below.

## macOS MAJOR > 0 requirement

jpackage on macOS rejects app-version starting with 0 ("The first number in an app-version
cannot be zero or negative") for both `createDistributable` (the `.app` bundle) and DMG
packaging. For 0.x.y releases, `build.gradle.kts` overrides `macOS.packageVersion` to a
1.x.y placeholder; this cascades to every macOS bundler. The app itself still reports
`BuildConfig.VERSION` (the real gradle.properties value) in `--version`, the window title,
and to the server — only what macOS shows in "Get Info" / Finder is affected.

Linux + Windows accept 0.x.y as-is.

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

## Nightly (rolling) builds

`release-nightly.yml` publishes a `nightly` pre-release on every push to `main` (and on
manual workflow dispatch). Same per-OS matrix as `release-tag.yml` — both call the reusable
`build-installers.yml` — with `version=0.X.Y-nightly.{shortsha8}` injected via `-Pversion=`
and `channel=nightly` passed via `-Pchannel=`.

**Channel = separate product.** Nightly's `build.gradle.kts` `-Pchannel=nightly` switch
gives nightly a different `packageName`, `bundleID`, `upgradeUuid`, and menu group from
stable. Result: a user with stable installed sees "Octi Nightly" as a distinct app — not as
an upgrade. Both can coexist. Solves the otherwise unsolvable problem of jpackage's
"every nightly has the same X.Y.Z `packageVersion`, so MSI/dpkg/rpm refuses to upgrade".

```
push to main / workflow_dispatch
  └─► release-nightly.yml
        └─ compute-version              derives 0.X.Y-nightly.{shortsha8} from gradle.properties + HEAD
        └─ build (calls build-installers.yml, channel=nightly)
              └─ build-linux            ubuntu-22.04 → octi-nightly_*.deb + .rpm + Octi-nightly.AppImage + .tar.gz
              └─ build-macos-arm        macos-14    → Octi-nightly-macos-arm64.dmg
              └─ build-macos-intel      macos-15-intel → Octi-nightly-macos-x64.dmg
              └─ build-windows          windows-2022 → Octi-nightly.msi
        └─ publish-nightly              mint App token, stale-SHA guard, force-move `nightly` tag,
                                        two-step upload (installers first, checksums + body last)
```

### Concurrency design

Workflow-level concurrency would cancel `publish-nightly` mid-upload along with the rest of
the workflow. Instead, each job sets its own concurrency group:

| Group | Jobs | `cancel-in-progress` |
|---|---|---|
| `release-nightly-build-${repo}` | compute-version, build | true — newer pushes supersede in-flight builds |
| `release-nightly-publish-${repo}` | publish-nightly | false — publish never cancelled mid-upload |

A **stale-SHA guard** inside publish-nightly aborts the run when main has moved past the
build's source commit. Prevents a slow stale build from clobbering a newer one.

### Asset names + URLs

Fixed per-OS — overwritten on every publish. The stable URLs that the README links to:

- `Octi-nightly-linux-x86_64.AppImage`
- `Octi-nightly-linux.tar.gz`
- `Octi-nightly-macos-arm64.dmg`
- `Octi-nightly-macos-x64.dmg`
- `Octi-nightly.msi`
- `octi-nightly_*.deb` / `octi-nightly-*.rpm` (filename carries jpackage's `X.Y.Z` from `packageName=octi-nightly`)

### Manual run

Actions → **Rolling nightly build** → **Run workflow** on `main`. Use when:
- The previous run cancelled mid-build due to concurrent push and nothing has happened since.
- You want to rebuild without committing a no-op.

The workflow refuses to run on any branch other than `main`.

### Rollback

If a broken commit landed and nightly is publishing it:

```
gh release delete nightly --cleanup-tag
git push origin --delete nightly   # if --cleanup-tag didn't remove the tag
```

The next push (or manual dispatch) recreates the release cleanly. Stable releases are
unaffected.

### One-time setup beyond the stable App-token prereqs above

- Ensure any tag protection ruleset covering the `nightly` tag lists the `d4rken-org-releaser`
  App as a bypass actor. If the ruleset is `v*`-scoped only, `nightly` is unaffected.

## Signing — still deferred

All artifacts are **unsigned** — stable AND nightly. macOS Gatekeeper + Windows SmartScreen
will warn on first launch. The README documents the bypass. Notarization is its own project
(Apple Developer membership + Windows Authenticode cert + secret wiring).
