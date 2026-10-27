# Claw session contracts

This topic extracts the transport-neutral contracts, immutable session metadata,
bounded registry/queues and turn lifecycle from the former combined Claw PR.
It does not start a channel, open a socket, read credentials or expose settings.

Session ownership includes IDE instance, connection epoch, provider and binding
generation. Reconnect snapshots replace one owner's roster atomically. An old
connection cannot heartbeat, drain or acknowledge another owner's queue.
Already dispatched messages survive a same-owner target refresh; waiting
messages for the old target are discarded. Idle duration uses a monotonic clock.

The registry holds at most 256 sessions and each inbound/control queue at most
64 messages. New registrations may reclaim an inactive entry; live capacity
overflow and duplicate/oversized snapshots are rejected without partial
replacement. Capabilities and display metadata are bounded and copied.
Epochs/revisions reject string coercion, fractional truncation, negative values
and numeric overflow before ownership comparison.

Focused JUnit tests cover reconnect/heartbeat fencing, stale owners, monotonic
idle, dispatch acknowledgement, turn ordering, immutable snapshots, exact JSON
numbers and bounded atomic admission. IPC/process coordination, routing,
durability, external transport and IDE/UI activation remain later topics.
