# Office display ownership contracts

This topic adds durable mode/recovery records and per-process overlay markers,
without starting a runtime or changing the floating pet yet.

The desktop lease writes `ENTERING_OFFICE` before collecting live markers.
Office can become visible only after each captured process confirms the exact
transition epoch, or its PID/start identity proves it exited. A missing marker
for a live process and an old acknowledgement never prove detachment. Retiring
a live process requires its caller to have physically detached the overlay.

Recovery is opt-in and compare-and-set against the complete prior intent.
Hidden Office retains its enabled intent; explicit exit disables recovery.
Unknown/corrupt schemas fail closed and are preserved. A retired lease cannot
restore overlay mode or overwrite a successor's intent.

Control, recovery and unique member files use separate explicit byte/name
budgets over the common ACL/path validator. Member writes cannot replace
coordinator or preference records. Tests use temporary private ACL roots and
cover exact epochs, missing markers, reused PIDs, retired processes, stale
leases, schema preservation and hidden/disabled recovery.
