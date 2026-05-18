# Octi Desktop

[![GitHub release](https://img.shields.io/github/v/release/d4rken/octi-desktop?include_prereleases&logo=github&label=Release)](https://github.com/d4rken/octi-desktop/releases/latest)
[![Code tests & eval](https://img.shields.io/github/actions/workflow/status/d4rken/octi-desktop/code-checks.yml?logo=githubactions&label=Code%20tests)](https://github.com/d4rken/octi-desktop/actions/workflows/code-checks.yml)
[![Gradle wrapper](https://img.shields.io/github/actions/workflow/status/d4rken/octi-desktop/gradle-wrapper-validation.yml?logo=gradle&label=Gradle%20wrapper)](https://github.com/d4rken/octi-desktop/actions/workflows/gradle-wrapper-validation.yml)
[![License](https://img.shields.io/github/license/d4rken/octi-desktop?label=License)](LICENSE)

Desktop companion for [Octi](https://github.com/d4rken-org/octi) — view your phone's modules and share files between your devices from your laptop. Talks to the existing [Octi Server](https://github.com/d4rken-org/octi-server) over the same end-to-end-encrypted protocol the Android app uses. No Google account, no separate backend.

## Downloads

Grab the latest installer for your OS from [GitHub Releases](https://github.com/d4rken/octi-desktop/releases/latest):

| Platform | File |
|---|---|
| Linux (Debian/Ubuntu) | `octi_<version>_amd64.deb` |
| Linux (Fedora/RHEL) | `octi-<version>.x86_64.rpm` |
| Linux (portable, any distro) | `Octi-<version>-linux-x86_64.AppImage` or `Octi-<version>-linux.tar.gz` |
| macOS — Apple Silicon | `Octi-<version>-macos-arm64.dmg` |
| macOS — Intel | `Octi-<version>-macos-x64.dmg` |
| Windows | `Octi-<version>.msi` |

Each release also publishes a `checksums.txt` with SHA256 hashes.

## Install

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

### Note on prerelease installers

Beta/RC installers carry the same OS-level package version as the eventual stable (jpackage limits versions to numeric `X.Y.Z`). If you have a beta installed and want to switch to the matching RC or stable, **uninstall first, then install the new build**. Stable→stable upgrades work normally.

## Link to your phone

1. On your Android phone, open Octi → **Sync services** → **Octi Server** → **Generate linking code**
2. On the desktop, paste the code into the welcome screen
3. Your devices appear in the dashboard within a few seconds

## Build from source

Requires JDK 21 (the Gradle toolchain provisions it automatically; system Java just needs to be 17+).

```bash
./gradlew run                  # run from sources
./gradlew createDistributable  # produce a runnable image under build/compose/binaries/main/app/Octi
./gradlew test                 # unit tests
./gradlew check                # tests + Kover coverage report
```

Native installers locally:

```bash
./gradlew packageDeb           # Linux .deb
./gradlew packageRpm           # Linux .rpm (needs rpmbuild)
./gradlew packageAppImage      # Linux app-image directory
./gradlew packageDmg           # macOS .dmg (macOS host only)
./gradlew packageMsi           # Windows .msi (Windows host only)
```

Each platform's installer can only be built on a host of that platform — jpackage doesn't cross-compile.

## Where it stores data

| OS | Path |
|---|---|
| Linux | `$XDG_CONFIG_HOME/octi` (config + encrypted credentials), `$XDG_DATA_HOME/octi` (caches) |
| macOS | `~/Library/Application Support/octi/` |
| Windows | `%APPDATA%\octi\` and `%LOCALAPPDATA%\octi\` |

Credentials are stored in the OS keystore (libsecret / Keychain / DPAPI). If the keystore is unavailable (e.g. headless Linux without D-Bus), an Argon2id-derived passphrase fallback kicks in and prompts on every launch.

## Compatibility

| Component | Required version |
|---|---|
| [Octi Server](https://github.com/d4rken-org/octi-server) | latest |
| [Octi Android](https://github.com/d4rken-org/octi) | post-[#306](https://github.com/d4rken-org/octi/pull/306) — older Android builds will fail to decode the desktop's `DESKTOP` meta payload |

Accounts created before AES-256-GCM-SIV rolled out can't share files from desktop — Tink's streaming AEAD that file shares use is GCM-SIV-only. The Settings screen shows the keyset type so you can tell which mode an account is on.

## Community

Join the [Octi Discord](https://discord.gg/s7V4C6zuVy).

## Contributing

Pull requests welcome. PR titles end up in the auto-generated release changelog, so write them as you'd want them to appear (e.g. `Add system tray icon`, not `tray`).

## License

GPL v3, matching the main Octi project. See [LICENSE](LICENSE).
