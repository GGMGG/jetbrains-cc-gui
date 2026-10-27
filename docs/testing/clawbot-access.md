# Claw binding and access persistence

Bot credentials, authorized sender IDs and route hints use IntelliJ PasswordSafe.
Each backend key has a separate service identity; legacy keys are migrated only
when their recorded username matches. Ordinary binding metadata contains no
token. Approved endpoints require HTTPS and an exact Weixin host boundary.
Malformed or unavailable credentials/metadata never produce an active binding.

Mutations advance the persisted binding revision even after a fresh service
instance, and overflow is rejected before touching credentials. Stored JSON has
independent bounded collections, strict integers, nesting and duplicate-field
checks; IPC retains its original smaller budget. Sender access is explicit,
bounded to 256 entries, fails closed and supports paged diagnostics. Persisted
route hints cannot restore a live conversation selection capability.

Non-sensitive progress settings use private runtime files with bounded reads
and atomic replacement. Invalid data is preserved for diagnosis. Tests use fake
credential/metadata backends and temporary private paths, including reconstructed
binding revisions, backend failures, legacy isolation, access revocation,
route recreation and strict persistence. No real PasswordSafe account or service
is contacted. Channel startup and IDE settings activation remain separate.
