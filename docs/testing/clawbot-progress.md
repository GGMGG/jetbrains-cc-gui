# Claw progress and interaction contracts

Progress follows the exact originating turn owner, runtime epoch and user-message
anchor. Public assistant text is copied under the message lock; transport I/O is
outside it. Rate limits, notification caps, bounded excerpts and retry deadlines
apply per request. A continuation explicitly rebinds its accepted turn rather
than following another tab or provider. Explicit selection first previews live
public text. If the current question is absent from memory, it reads the selected
persisted session's latest display turn, using the cancellable history contracts
from official PR #1879. It excludes tool input/output and reasoning blocks,
never replaces the live transcript, and stops cooperatively after 2.5 seconds.
The IDE integration schedules this read outside its polling loop. Runtime,
provider and persisted-session changes invalidate the result, and newer live
messages take precedence. Failed reads are distinct from empty conversations.

Question exchanges carry a fresh revision and exact interaction IDs. Multiple
questions require an explicit item selector, stale answers fail closed and long
permission/plan content directs the user to the IDE rather than offering approval
for unseen content. Supported explicit plan choices remain distinct. Mailbox
routing and capability checks are prerequisites; no external channel is started.

Fixtures cover exact-turn replacement, concurrent sends, retries, per-turn limits,
public-text filtering, async question answers and explicit approval decisions.
Preview regressions also cover empty-memory restoration, one-turn native reads,
long tool sequences, stale bindings, cancellation, read failures and deadlines.
