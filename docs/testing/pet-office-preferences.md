# Office window preferences

This slice adds revision-checked Office name, topmost and zoom edits, validated
backup recovery, debounced normal-window bounds, and geometry restoration after a
display is removed. The standalone page applies confirmed settings by a monotonic
page generation; a late scene cannot overwrite a newer save acknowledgement.

The saved schema retains the existing prototype's display/cache/theme fields so
later slices can consume them without resetting existing choices. This slice edits
only name, topmost and zoom. A private-file helper owns the filename/byte allowlist,
ACL checks, no-follow reads and atomic replacement; it has no IPC endpoint or asset
enumeration responsibility.

Settings live in the verified Windows desktop/user runtime namespace. An OS lease
serializes preference writes and window ownership across IDE processes. Forwarding
an open request to another IDE is introduced by the IPC slice; this slice reports
an unavailable window if another process currently holds the lease.

The IO worker commits a validated record before scheduling native window changes.
It acknowledges success after the EDT applies them. A native window failure
restores the previous window settings and performs a revision-checked file rollback.
Rollback uncertainty is reported explicitly. Closing the page retires queued UI
authority; an already accepted persisted edit remains readable on reopening.

The form preserves dirty drafts, keeps their original revision during polling,
prevents duplicate saves, aborts pending reads on unmount and allows an explicit
reload of confirmed values after a lost response. Bounds updates preserve all other
preferences. Backup recovery never restores communication endpoints or authority.

Automated checks cover real file leases (including separate JVMs and forced fixture
termination), private temporary ACL roots, backup/schema/revision validation,
off-screen geometry, queued acknowledgements, rollback, expired page authority,
dirty drafts, late scenes and browser behavior at four viewport sizes. Actual
native focus, topmost and monitor transitions require a Windows IDE manual check.
