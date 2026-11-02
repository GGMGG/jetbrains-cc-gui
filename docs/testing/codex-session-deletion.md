# Guarded Codex session deletion

This review unit depends on database maintenance and native idle-thread unload.
It adds one dedicated deletion handler rather than adding mutations to the
inventory reader. History and storage use the same confirmed SQLite/file receipt.
The legacy history messages also use guarded storage deletion, and retain the
official independent, serialized favorite/title cleanup services.

The handler validates bounded targets, explicit origin and canonical operation
identity before unloading a native owner. It never interrupts a running target
or an owned pending question. Only the current window's verified idle owner can
be unloaded. Binding, provider, channel and activity are checked again when the
unload acknowledgement arrives. Native writer leases and database dependencies
are rechecked for the resolved parent/child family before any mutation.

Queued resumes take a shared cross-process lease for their target. Deletion
takes the exclusive counterpart for the whole family before persisting intent
or deleting data. A busy lease refuses deletion without side effects; a pending
resume for another session does not block selected-session deletion. Send,
preconnect, compact and review check permanent-deletion receipts while retaining
their resume lease. Pending maintenance also refuses a new native thread.
Native provider fixtures use their own coordination directories.

The UI reuses history selection and confirmation components, excludes running
or unavailable targets, and removes rows only after a confirmed deletion receipt.
Request ID, exact targets and origin are saved before dispatch. Lost responses
and metadata cleanup failures retain the original request for explicit status
checking or retry, including after a page remount. A retry cannot adopt a newer
selection. Confirmed deletion notifications update history and close an affected
active session once; unrelated providers keep their existing behavior.

## Validation

- Full Java suite: 2,270 discovered, 2,252 passed, 18 skipped, zero failures or
  errors; Checkstyle passed.
- Full frontend suite: 250 files and 2,595 cases passed. This includes 101
  affected storage/history cases. Test TypeScript compilation and production
  build passed.
- New fixtures cover native resume/deletion contention, child-family ownership,
  all four writer entry points, pending maintenance, idle unload races,
  metadata failures, lost responses, remount recovery and duplicate receipts.

SQLite fixtures and the native protocol peer are disposable. These checks do
not establish live IDE rendering, account/model access or actual Linux/macOS
filesystem mutation behavior. Platform capability checks remain enforced.
