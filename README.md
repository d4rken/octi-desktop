<img src="https://github.com/d4rken-org/octi/raw/main/fastlane/metadata/android/en-US/images/featureGraphic.jpg" width="400">

# Octi Desktop

[![GitHub release](https://img.shields.io/github/v/release/d4rken-org/octi-desktop?include_prereleases&logo=github&label=Release)](https://github.com/d4rken-org/octi-desktop/releases/latest)
[![Code tests & eval](https://img.shields.io/github/actions/workflow/status/d4rken-org/octi-desktop/code-checks.yml?logo=githubactions&label=Code%20tests)](https://github.com/d4rken-org/octi-desktop/actions/workflows/code-checks.yml)
[![Gradle wrapper](https://img.shields.io/github/actions/workflow/status/d4rken-org/octi-desktop/gradle-wrapper-validation.yml?logo=gradle&label=Gradle%20wrapper)](https://github.com/d4rken-org/octi-desktop/actions/workflows/gradle-wrapper-validation.yml)
[![License](https://img.shields.io/github/license/d4rken-org/octi-desktop?label=License)](LICENSE)
[![Discord](https://img.shields.io/badge/Discord-Octi-5865F2?logo=discord&logoColor=white)](https://discord.gg/s7V4C6zuVy)

Desktop companion for [Octi](https://github.com/d4rken-org/octi) — view your phone's modules and share files between your devices from your laptop. Talks to the existing [Octi Server](https://github.com/d4rken-org/octi-server) over the same end-to-end-encrypted protocol the Android app uses. No Google account, no separate backend.

## Install

Two release channels are published as [GitHub Releases](https://github.com/d4rken-org/octi-desktop/releases):

- **[Stable](https://github.com/d4rken-org/octi-desktop/releases/latest)** — the build to use unless you have a reason not to.
- **[Nightly](https://github.com/d4rken-org/octi-desktop/releases/tag/nightly)** — rebuilt on every merge to `main`, installs as a separate "Octi Nightly" app that coexists with your stable install.

Each release publishes installers for Linux, macOS (Apple Silicon + Intel), and Windows, plus a `checksums.txt` with SHA-256 hashes.

### Linux

```bash
# Debian/Ubuntu
sudo apt install ./octi_<version>_amd64.deb
sudo apt install libsecret-tools     # required for OS keystore credential storage

# Fedora/RHEL
sudo dnf install ./octi-<version>.x86_64.rpm
sudo dnf install libsecret           # provides secret-tool

# Portable
chmod +x Octi-<version>-linux-x86_64.AppImage && ./Octi-<version>-linux-x86_64.AppImage
# or
tar xzf Octi-<version>-linux.tar.gz && ./Octi/bin/Octi
```

`libsecret-tools` is required for credential storage on Linux. Without it the app falls back to a per-launch passphrase prompt.

### macOS

The current releases are **unsigned** — Gatekeeper will refuse to launch them on first try. To open:

1. Open the `.dmg` and drag Octi to `/Applications`
2. Right-click `/Applications/Octi.app` → **Open** → confirm in the warning dialog (once per machine)

Code-signing + notarization require a paid Apple Developer membership; until then, this is the workaround.

### Windows

The MSI is **unsigned** — SmartScreen will warn on first launch. To install:

1. Double-click the `.msi`. SmartScreen pops up: click **More info** → **Run anyway**
2. Follow the installer prompts

Code-signing on Windows requires an Authenticode certificate; planned for a later release.

### From source

Requires JDK 21 (the Gradle toolchain provisions it automatically; system Java just needs to be 17+).

```bash
./gradlew run                  # run from source
```

## Link to your phone

1. On your Android phone, open Octi → **Sync services** → **Octi Server** → **Generate linking code**
2. On the desktop, paste the code into the welcome screen
3. Your devices appear in the dashboard within a few seconds

## Where it stores data

| OS | Path |
|---|---|
| Linux | `$XDG_CONFIG_HOME/octi` (config + encrypted credentials), `$XDG_DATA_HOME/octi` (caches) |
| macOS | `~/Library/Application Support/octi/` |
| Windows | `%APPDATA%\octi\` and `%LOCALAPPDATA%\octi\` |

Credentials are stored in the OS keystore (libsecret / Keychain / DPAPI). If the keystore is unavailable (e.g. headless Linux without D-Bus), an Argon2id-derived passphrase fallback kicks in and prompts on every launch.

## Community

Join the [Octi Discord](https://discord.gg/s7V4C6zuVy).

## Octi ecosystem

* [Octi](https://github.com/d4rken-org/octi) — the Android app.
* [Octi Server](https://github.com/d4rken-org/octi-server) — the end-to-end encrypted sync-server.
* [Octi Web](https://github.com/d4rken-org/octi-web) — browser client for the same sync-server.
* [Octi Desktop](https://github.com/d4rken-org/octi-desktop) — this Compose Multiplatform desktop client (Linux / macOS / Windows).

## License

Octi Desktop's code is available under a [GPL v3](LICENSE) license, this excludes:

* Octi icons, logos, mascots, marketing materials and assets.
* Octi animations and videos.
* Octi documentation.
* Translations.
