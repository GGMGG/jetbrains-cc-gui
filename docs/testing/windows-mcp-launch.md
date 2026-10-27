# Windows MCP launch and refresh

The STDIO verifier and tool reader resolve extensionless Windows commands with
one asynchronous, five-second-bounded `where.exe` lookup. PATHEXT selects the
executable shim. Native executables run without a shell; batch shims quote the
resolved executable and each argument so spaces and parentheses remain intact.
Shell chaining, expansion and quote syntax are rejected before a batch launch,
including in the resolved PATH entry.

Full refresh clears tool caches, reloads the server catalog and connection
status, and waits for the new catalog before requesting tools for the single
expanded, enabled card. Deleted and disabled servers are not queried. The
persistent Codex app-server remains the source of Codex status and tools; this
patch does not modify the retired Java MCP status implementation.

Validation passed nine Node tests and 24 MCP frontend tests, test TypeScript
compilation, and the production frontend build. The Windows test launches real
temporary `.cmd` shims from directories containing spaces through both status
verification and tools/list, covering explicit paths and bare PATH commands.
It also preserves spaces, parentheses and an empty argument across the shim.
No external MCP service or live account is required. Other OS execution and
IDE rendering are not established by these local checks.
