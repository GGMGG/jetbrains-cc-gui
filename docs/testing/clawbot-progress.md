# Claw progress and interaction contracts

Progress follows the exact originating turn owner, runtime epoch and user-message
anchor. Public assistant text is copied under the message lock; transport I/O is
outside it. Rate limits, notification caps, bounded excerpts and retry deadlines
apply per request. A continuation explicitly rebinds its accepted turn rather
than following another tab or provider. Preview uses in-memory public text only,
excluding tool input/output and reasoning blocks; it never reads disk history.

Question exchanges carry a fresh revision and exact interaction IDs. Multiple
questions require an explicit item selector, stale answers fail closed and long
permission/plan content directs the user to the IDE rather than offering approval
for unseen content. Supported explicit plan choices remain distinct. Mailbox
routing and capability checks are prerequisites; no external channel is started.

Fixtures cover exact-turn replacement, concurrent sends, retries, per-turn limits,
public-text filtering, async question answers and explicit approval decisions.
