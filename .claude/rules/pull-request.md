# Pull Request Guidelines

See [commit-guidelines.md](commit-guidelines.md) for commit message format — PR titles follow the same rules.

## PR Titles

Same rules as commit titles — imperative mood, no module prefix, 50-60 chars max. PR titles are user-visible in release notes (`generate_release_notes` uses them as changelog entries), so write them as you'd want them to appear in a changelog.

- ✅ "Add system tray icon"
- ✅ "Fix WebSocket reconnect after sleep on macOS"
- ❌ "tray"
- ❌ "fix bug"

## PR Description Format

### What changed

Plain-language summary of the user-visible impact. Describe the problem that was fixed or the feature that was added from the user's perspective. Avoid internal class or method names.

For non-user-facing PRs (refactors, tests, CI, dependency bumps): write `No user-facing behavior change.` followed by a brief internal description.

### Technical Context

Explain what's hard to extract from the diff alone. Bullet points, scannable. Focus on:

- **Why** this approach was chosen (and alternatives considered/rejected)
- **Root cause** for bug fixes (the diff shows the fix, not what caused it)
- **Non-obvious side effects** or behavioral changes not apparent from the code
- **Review guidance** — what's tricky or deserves close attention

Don't restate what's visible in the diff (file names, renames, line-level changes).

### Example

```markdown
## What changed

Fixed a crash on Linux when libsecret isn't installed and the user opts into the passphrase fallback for the keystore.

## Technical Context

- Root cause: `LibsecretKeystore.unlock()` propagated the JNA `UnsatisfiedLinkError` instead of mapping it to the project's `KeystoreUnavailable` exception, so the fallback path never fired
- Chose to widen the catch to `LinkageError` (not just `UnsatisfiedLinkError`) because Conscrypt's host check throws `NoClassDefFoundError` on some musl distros
- Side effect: hosts that previously crashed at first launch will now silently fall back to the passphrase prompt — note this in the release notes
```

## Labels

Before opening a PR, look up the repo's labels and apply the ones that match — don't invent new labels.

```bash
gh label list -R d4rken-org/octi-desktop
```

(Or use the GitHub MCP `list_labels`.) Typically apply:

- One component label (`c: module/...`, `c: sync`, `c: sync/octi`, `c: sync/gdrive`) when the change touches a specific module or sync connector
- One change-type label (`bug`, `enhancement`, `documentation`, `Build/Deploy`) describing the nature of the change

Pass them on PR creation: `gh pr create --label "bug" --label "c: sync/octi"`. If nothing fits cleanly, leave it unlabeled rather than picking a wrong one.

## Other Conventions

- **Issue references**: Use `Closes #123`, `Fixes #123`, or `Resolves #123` in the description (auto-closes the issue on merge)
- **Breaking changes**: Mark the title with `BREAKING:` prefix when wire/API/CLI compatibility breaks
- **Co-authors**: Use `Co-authored-by: Name <email>` in the commit body for pair work
