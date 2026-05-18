# Commit Message Guidelines

Same convention as the main Octi project and sync-server.

## Format

- Imperative mood, present tense ("Add feature" not "Added feature")
- First line: 50–70 characters
- No module prefix — flat commit messages
- Optional blank line + detailed body. Body explains *why*, not *what*.

## Examples from this repo

```
Drop reported version to 0.1.0-dev now that octi#308 scopes Android gates by platform
Adopt PR #306 meta schema with DESKTOP type and osType/osVersionName
Raise online threshold to 20 min to match Android sync cadence
Bump reported version to 1.0.0-dev for Android compatibility
Add GPLv3 LICENSE and project README
Add opt-in debug RPC endpoint for app orchestration
```

## Special formats

- **Dependency upgrades**: `Upgrade {dep} from {old} to {new}`
- **Release commits** (when we ship): `Release: {version}`

## Linking to upstream PRs

When the desktop change is motivated by a change in the Android app or server, reference it with the short form `octi#NNN` or `octi-server#NNN`. Example: `now that octi#308 scopes Android gates by platform`. Readers can resolve those without the full URL.
