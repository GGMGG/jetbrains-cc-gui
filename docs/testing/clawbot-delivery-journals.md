# Claw execution journals and cursor state

Gateway execution claims precede enqueue and retain uncertain outcomes rather
than replaying accepted work after a crash. IDE execution claims serialize across
instances and processes under a private lock file, bind STARTED ownership to PID
plus start time, and reconcile only a dead owner to UNKNOWN. Terminal and unknown
records are bounded and never silently evicted to admit new work. Journals contain
identities/state/timestamps, not prompt bodies, answers, credentials or recipients.

Reads and atomic replacements use owned, nonlinked private runtime files with
explicit per-file byte limits and strict JSON integers/duplicates/nesting. A
separate 2048-entry journal budget does not widen wire or PasswordSafe limits.
Damaged data fails closed and remains untouched. The iLink cursor and bounded
seen/uncertain IDs are persisted separately; encrypted replies are a later topic.

Tests cover reconstructed services, dead/live process identity, concurrent claims,
capacity, duplicate execution and privacy. Fixtures use only private temporary
roots. A process lock has a three-second acquisition deadline and cancellation
preserves interruption; no journal I/O belongs on the IDE event thread.
