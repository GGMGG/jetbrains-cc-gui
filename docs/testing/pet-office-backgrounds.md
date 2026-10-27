# Office shared preferences and backgrounds

Page-scoped typed storage admits only declared operations and floor filenames.
Backgrounds use one staged upload per page, a 15-second lifetime, ordered bounded
chunks, canonical Base64 and digest checks before atomic replacement. The last
complete image survives malformed, stale, partial or failed transfers. At most
4 MiB per image and 16 MiB across floors is retained. Static PNG/JPEG/WebP are
admitted with dimension/frame limits; animated PNG/WebP are rejected.

Background file permissions/ownership reuse the private file boundary with a
separate explicit floor budget. They do not extend coordinator endpoint file
authority. Page lifetime and leader lease are rechecked before storage commits.
Shared visual preferences use revision CAS and validated backup recovery; they
cannot change native name/topmost/zoom without the window acknowledgement
controller. Local Pet choices use the existing read-permit admission boundary.

Temporary-root tests cover takeover, revision conflicts, long session appearance
keys, backup recovery, transfer corruption, page retirement, quotas, static image
formats, typed choices and rejection of native-window preference bypass. Renderer
requests and scene UI integration remain a subsequent topic.
