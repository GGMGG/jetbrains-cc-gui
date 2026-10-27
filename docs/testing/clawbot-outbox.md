# Durable outbound delivery

This topic depends on the private runtime/journal and binding foundations. It introduces no network polling or IDE startup hooks.

- Pending messages and retained complete replies use AES-GCM with fresh nonces and a binding-derived, domain-separated key. A binding change also invalidates the in-memory plaintext cache.
- Queue admission is atomic, reserves capacity for lifecycle messages, preserves predecessor ordering, and archives ambiguous outcomes without automatically replaying them.
- Read-only follower status never reconciles another leader's in-flight receipts. A new leader converts interrupted sends to `UNKNOWN`.
- Outbound receipts contain UUIDs, times and stable error codes; message text, recipient and context remain in encrypted files only.
- Every state file passes the shared private-directory, ownership, no-follow, bounded-read and atomic-replace checks. Corrupt records are retained and reported unavailable.
- Protective local send budgets survive restart. These local budgets are not advertised as provider quotas.

Validation uses the ClawBot Java package and Checkstyle. Fixtures cover encryption/reload, wrong bindings, capacity reservation, restart reconciliation, fractional or duplicate fields, expiry and explicit recovery claims.
