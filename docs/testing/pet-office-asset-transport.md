# Pet Office cross-IDE artwork transport

Each publishing IDE owns a separate authenticated art endpoint. At most two
streams serve immutable catalogs or 4 MiB images, with bounded frames, ordered
chunks, canonical Base64, digest verification, exact request correlation and
absolute deadlines. Artwork never occupies the control/snapshot stream. A
partial transfer cannot replace complete bytes or delay navigation polling.

Endpoint registration belongs to the current authenticated participant
connection and is cleared on reconnect. The coordinator admits only current
source catalogs, rechecks page/connection/appearance revisions while fetching,
and limits remote work to two concurrent requests. No file paths or local Pet
identifiers cross the process boundary. Metadata is sampled from immutable
project/global configuration without attaching floating overlays.

Real TCP fixtures cover chunking, stale generations, ownership and independent
control traffic. Controlled child-JVM fixtures exercise remote artwork and exact
display-detachment acknowledgements, retirement and a source crash. They use
temporary art/private pipes and terminate only their owned test processes.
Renderer asset delivery and background storage remain subsequent topics.
