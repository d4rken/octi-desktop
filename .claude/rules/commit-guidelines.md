# Commit Message Guidelines

Same convention as the main Octi project and sync-server. PR titles follow the same rules — see [pull-request.md](pull-request.md).

## Format

- Imperative mood, present tense ("Add feature" not "Added feature")
- First line: 50-60 characters max
- No module prefix — this project uses flat commit messages
- Optionally add a blank line and detailed description body. Body explains *why*, not *what*.

## Examples from History

```
Adopt PR #306 meta schema with DESKTOP type and osType/osVersionName
Raise online threshold to 20 min to match Android sync cadence
Bump reported version to 1.0.0-dev for Android compatibility
Add GPLv3 LICENSE and project README
Add opt-in debug RPC endpoint for app orchestration
Rename "nightly" rolling channel to "canary"
Fix Xvfb startup in screenshots workflow
```

## Special Formats

- **Release commits**: `Release: {version}` (e.g., `Release: 0.14.0-rc0`)
- **Dependency upgrades**: `Upgrade {dependency} from {old} to {new}` (e.g., `Upgrade AGP from 8.12.2 to 8.13.2`)

## Linking to upstream PRs

When the desktop change is motivated by a change in the Android app or server, reference it with the short form `octi#NNN` or `octi-server#NNN`. Example: `now that octi#308 scopes Android gates by platform`. Readers can resolve those without the full URL.
