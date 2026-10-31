# Claw conversation routing

Pure routing follows the bounded session contract topic, without IPC, accounts
or channel startup. Conversation selection is an in-memory capability bound to
the exact session generation, provider and owner; persisted hints cannot recover
that authority after restart. Five-minute session lists and route revisions
fence stale numeric selection and question answers. Preview requests are bounded
and expire after five seconds.

The router checks sender access before dispatch, distinguishes commands from
ordinary text, deduplicates message identities and exposes execution-claim hooks
for the later durable delivery topic. Unknown outcomes are retained rather than
silently replayed. Progress interval contracts are included, while persistence,
notification publishing and external-channel activation remain separate.

Fixtures cover stale routing, selection changes, owner replacement, independent
question controls, preview lifetime, idempotency and uncertain execution. Tests
that use the credential backend belong to the later access/storage topic.
