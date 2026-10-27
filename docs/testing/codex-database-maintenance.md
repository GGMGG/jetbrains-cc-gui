# Codex database maintenance

This review unit builds on read-only storage inventory and runtime relocation
admission. It exposes database status, compaction, orphan preview and confirmed
orphan cleanup. Session deletion and its history integration remain a dependent
change; no session deletion action is registered here.

Every write has a caller-supplied UUID, a durable journal and native writer
leases. Database identity, schema, filesystem containment and runtime ownership
are checked again under the mutation gate. Compaction handles WAL checkpoints
explicitly. Orphan cleanup requires a preview token that still matches the
current database. Advanced maintenance is supported only on local Windows
storage; other platforms receive a capability error before a write.

An interrupted maintenance journal blocks new ordinary process admission and
relocation, including reuse of an already-held shared process lock. Recovery
can reacquire the maintenance lease and reconcile the same operation. The UI
stores request identity before dispatch, retains it across a lost response or
remount, and retries that identity instead of creating another mutation.
Status refresh invalidates previous authorization while new reads are pending.

## Validation

- Full Java suite: 2,239 discovered, 2,222 passed, 17 skipped, no failures or
  errors; Checkstyle passed.
- Affected frontend suite: 45 passed; test TypeScript compilation and production
  build passed.
- Disposable SQLite fixtures cover native lock contention, WAL readers,
  filesystem identity changes, schema rejection, crash boundaries, durable
  restart reconciliation and orphan preview invalidation.
- Frontend fixtures cover unsupported platforms, confirmation before dispatch,
  durable identity before dispatch and recovery after a lost response.

The checks do not modify an installed Codex home and do not establish live IDE
rendering or actual Linux/macOS mutation behavior.
