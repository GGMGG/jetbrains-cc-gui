# Hooks compatibility and review checks

Codex declarations use PascalCase event names and nested per-handler entries in
`config.toml` or `hooks.json`. The enable preference is stored in the user's
`[hooks.state."<source-path>:<snake-event>:<group>:<handler>"]` table. Changing a
project hook's preference changes only the user configuration. The declaration
and referenced scripts are preserved. Native keys use physical source paths,
including a linked CODEX_HOME. An absent preference defaults to enabled.
Enabled preferences do not establish that a hook is trusted or will execute.

The reference is the pinned official `rust-v0.163.0` implementation:

- [Hook configuration and user state](https://github.com/openai/codex/blob/rust-v0.163.0/codex-rs/config/src/hook_config.rs)
- [Native event labels and state keys](https://github.com/openai/codex/blob/rust-v0.163.0/codex-rs/hooks/src/lib.rs)
- [Discovery and configuration layers](https://github.com/openai/codex/blob/rust-v0.163.0/codex-rs/hooks/src/engine/discovery.rs)

Run the real CLI contract check with an installed 0.163.0 entry point:

```powershell
$env:CC_GUI_HOOKS_TEST_CLI = '<absolute path to codex.js or codex executable>'
node --test test/codex-hooks-native.test.mjs
```

The check uses a disposable home, strips inherited credential variables, and
only calls `hooks/list`. It verifies default enablement, exact native keys,
independent handlers, disable/re-enable preferences for both declaration formats,
and unchanged JSON declarations. It requires no account or model request and
does not execute hook commands. Missing CLI configuration is an explicit skip.
The catalog keeps runtime-version verification conservative because installed
CLI versions can differ. Codemoss script execution semantics remain unverified;
new directory-based quarantine toggles are unavailable. Existing disabled-script
records remain recoverable with path, revision, content and attribute checks.

Claude managed toggles retain matcherless wrappers and non-command handlers.
Only the root JSON hooks value is replaced; unrelated fields, nulls, escaped
strings and surrounding newline bytes are preserved. Private per-source user
storage holds state and at most 20 source backups, rather than writing backups
or state into a project. Multiple disabled siblings retain their original order
regardless of the order in which they are restored. Legacy records without the
new ordering metadata retain their previous restoration behavior.

Validation completed on Windows:

- Full Java suite before the final ordering regression: 2,050 discovered,
  2,033 passed and 17 skipped; Checkstyle passed.
- The final focused hooks, handler and Codex-home suite passes: 41 discovered,
  40 passed and one skipped; Checkstyle passes. It covers bare/wrapped handler
  ordering in both restore orders and linked native homes.
- Native 0.163.0 contract test passes with no skips.
- All 44 affected frontend cases, test types and the production build pass.
  Regression coverage includes newline preservation, long-insertion
  diff alignment, refused dispatches, timeouts, A/B/A source selection, stale
  responses and late toggle/save confirmations. All ten locales have the full
  Hooks namespace, with UTF-8 encoding and matching interpolation placeholders.

Linux/macOS execution and interactive IDE/JCEF acceptance are not established by
these Windows checks.
