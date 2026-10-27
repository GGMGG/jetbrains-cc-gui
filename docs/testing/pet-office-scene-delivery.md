# Acknowledged renderer transfers

This topic adds a typed page transport on top of the Office metadata, immutable appearances and background-storage foundations. The production window is unchanged until the page integration topic.

- Complete scenes are validated per participant before aggregation, capped at 256 sources and 2 MiB, and transferred in 32 KiB chunks with a SHA-256 digest.
- A page has one outstanding scene. Newer metadata cannot accumulate a renderer queue while the previous transfer is unacknowledged or still scheduled on the EDT.
- Acknowledgements are fenced by page, transfer and sequence. Resynchronization and late acknowledgements cannot authorize a retired appearance.
- Image requests refer to an appearance from the last acknowledged scene and retain byte-length/digest checks.

Validation: focused Office Java tests and Checkstyle, including a full 256-source scene, malformed participants, chunk limits, stale acknowledgements and deadline behavior.
