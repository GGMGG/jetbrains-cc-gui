# Windows Office desktop runtime

This topic activates the previously isolated transport and display contracts.
One Windows desktop lease owns the standalone window; other GUI IDEs publish
their bounded session samples and forward show, exit and exact-tab requests.
Ownership is acquired before checking renderer support, so a sender-only IDE can
show the existing window without acquiring renderer authority.

Entering Office first revokes overlay permission and waits for each live IDE's
EDT detachment acknowledgement for the exact mode epoch. Explicit exit persists
disabled intent before disposing the native window, then restores overlays.
Closing the window hides it while retaining enabled intent; recovery preserves
that hidden state. Unknown or corrupt mode/recovery data fails closed.

Navigation carries its original deadline across page queueing and IPC polling.
Page lifetime, participant connection and exact tab binding are rechecked before
activation. Cross-IDE project identifiers remain distinct. Shutdown closes active
sockets immediately; final endpoint/mode cleanup runs before releasing the lease
and after native renderer disposal.

Validation: 138 focused Java tests and Checkstyle passed. Frontend: 18 focused
tests, test TypeScript and the Office production build passed. Playwright: 24
cases passed across desktop, narrow, short and mobile viewports, covering peer
identity, exact activation, acknowledged exit and retry, settings and read errors.
Real TCP fixtures cover aggregation and handoff contracts. Native JCEF focus,
Windows desktop identity, overlay detachment and crash recovery still require
real IDE acceptance; browser fixtures do not claim to validate native windows.

Custom assets and scene artwork remain subsequent topics. This topic introduces
no model requests or external channel startup.
