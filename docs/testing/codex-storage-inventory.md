# Read-only Codex storage inventory

This patch depends on the runtime admission and Windows data relocation
foundations, plus session execution-state tracking. It extracts the read-only
part of the storage feature into a separate review unit. Maintenance, deletion,
session relationship editing and Office remain separate changes.

The settings panel lists sessions across projects and archives, applies search,
sort and pagination, and reads bounded transcript pages only when requested.
Content is displayed as text. Disk usage is an explicit, cancellable scan;
cancellation is registered before background dispatch so an early cancel is
retained. Request IDs and abort signals prevent late replies from replacing a
newer view. Failed bridge delivery is reported immediately.

SQLite reads use a WAL-aware native read-only connection with query-only mode.
Missing databases are never created. Fixture tests keep a live WAL writer open,
verify that committed changes are visible, refuse a DELETE, and confirm the
main database bytes are unchanged. Directory identities and containment checks
are revalidated, and storage reads participate in relocation admission.

## Validation

The complete Java suite passed 2,167 cases: 2,151 passed and 16 skipped, with no
failures or errors. Checkstyle passed. The affected frontend suite passed all
41 cases; test TypeScript compilation and the production build passed.

These checks use disposable fixtures and do not alter an installed Codex home.
They do not establish live IDE rendering or cross-platform filesystem behavior.
The original inventory review unit exposes no mutation command. Dependent
maintenance and session-deletion changes retain their own schema, platform,
activity, ownership and recovery gates, documented in their separate test notes.
