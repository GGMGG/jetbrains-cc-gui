# Office IPC contracts

This slice defines the bounded stream framing, typed endpoint metadata,
domain-separated mutual authentication proofs, participant snapshot authority and
navigation mailboxes. It opens no socket and activates no application service.
The transport and IDE runtime integration are subsequent slices.

One JSON codec validates native page, preference and IPC payloads. Frame lengths
are checked before body allocation; malformed UTF-8, duplicate fields, unknown
schemas, excessive depth and oversized arrays are rejected. Only the typed legacy
preference appearance map admits its larger bounded composite keys.

A new authenticated connection retires its predecessor. Regressed/malformed
snapshots cannot replace the last complete set. Transport heartbeats cannot refresh
backend observation age or invent a terminal outcome. Capacity rejections are
atomic and visible, and stale/disconnected sources become unknown before retiring.
Recent result identity includes its source and turn, so two participants using the
same terminal event name retain separate results.

Navigation commands are bound to an authenticated connection, page, route
generation, interaction identity and deadline. Closing a page discards commands
that have not crossed the dispatch boundary. A dispatched focus action cannot be
undone by a later page closure. Old connections, guessed tickets, duplicates and
late replies cannot acquire replacement authority.

Tests cover typed codecs and authentication coordinate changes, source capacity,
stale observations, reconnect callbacks, atomic snapshot admission and navigation
mailbox deadlines. Real loopback handshakes, listener teardown and multi-process
runtime behavior belong to the transport/integration slices.
