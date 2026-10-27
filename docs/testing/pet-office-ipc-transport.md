# Office loopback transport

This topic builds on the typed participant/navigation contracts. It adds the
actual IPv4 loopback server/client and private endpoint discovery; it does not
start an application service or replace the local Office window yet.

- A current desktop lease owns endpoint publication/removal. ACL-validated,
  bounded discovery records reject unsafe paths; a retired lease or different
  endpoint cannot erase a successor.
- Client and server prove distinct roles using HMAC-SHA256. The secret is never
  sent on the wire or included in endpoint diagnostics. Every reply checks its
  protocol, leader, connection, request, type and result.
- Concurrent calls share one serialized stream. An absolute request deadline
  closes that exact active request; close interrupts blocked socket IO.
- Complete snapshots transfer through bounded 64 KiB frames and 32 KiB chunks.
  The server checks canonical base64, ordering, total length, SHA-256, capture
  age and the participant schema before atomic admission. Partial replacement
  never changes the last committed roster.
- Handshakes, workers, participants, snapshots and navigation queues are
  bounded. Lease loss, stale connection identity and shutdown fence commands.
  Closing the server retires its listener, accepted sockets and executors.

Run all Office JUnit tests plus Checkstyle. Real loopback fixtures cover mutual
authentication/reflection, slow handshake, partial/corrupt snapshots, capacity,
reconnection, concurrent publish/show/heartbeat, navigation acknowledgements,
lease loss and stream disposal. Private file tests use a temporary ACL root;
they do not access the user's live Codex configuration or desktop namespace.
