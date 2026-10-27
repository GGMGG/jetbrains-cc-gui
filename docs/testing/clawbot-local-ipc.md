# Claw local IPC and process coordination

This topic adds the opt-in IPC/process/client foundation. It does not start a
gateway, connect an external service, load provider credentials or attach the
IDE chat integration.

The unmerged prototype transport is upgraded to protocol 2. A fresh server
nonce and HMAC-SHA256 prove the coordinator before the client authorizes the
socket. Distinct roles, port, client identity/epoch and both nonces bind the
proofs; replay/reflection cannot authorize another handshake. The shared
secret never appears in a packet. Welcome and business replies must match the
same request and coordinator identity. Requests cannot impersonate a different
client from the handshake. Endpoints accept literal loopback addresses only.

Each transaction has an absolute deadline, including queueing and trickled
bytes. Four workers, sixteen queued connections and 64 KiB frames bound work.
Shutdown closes every accepted socket and stops the executors. Strict UTF-8,
depth/field/array limits, duplicate-key rejection and exact numeric decoding
apply before business dispatch.

The OS lock is the sole leader authority. Private discovery/secret files reject
links, unsafe parents, foreign owners, broad permissions and oversized reads.
New Windows files use owner ACLs; existing SYSTEM/Administrators access is
accepted as privileged. POSIX group/other permissions are rejected. Atomic
leader replacement never deletes a corrupt/foreign record during cleanup;
lease cleanup still releases the OS lock if record validation fails.

JUnit fixtures use real loopback sockets and temporary runtime roots. They
cover spoofed coordinators, replay, stale replies, slow handshakes, shutdown,
malformed framing, secret persistence and lock release after corrupt metadata.
Live runtime/configuration directories are never used by these tests.
