# AI data runtime admission foundation

This preparatory patch supplies the admission and ownership primitives used by
the directory relocation patch. It does not activate relocation, add settings UI
or change existing process launchers. Those call sites are connected together in
the next patch so they cannot use a relocated home before recovery is available.

## Contract

- A migration intent blocks new process admission before idle runtimes stop.
  Existing active work rejects relocation. Runtime callbacks run outside the
  admission lock.
- A process start before commit cancels relocation and waits for rollback. A
  start after commit waits for relocation to finish.
- Process and bounded read permits participate in the same stable Windows
  shared/exclusive file-lock protocol. Owner metadata records the protocol
  version, process identity and operation without moving with managed data.
- A bound process permit survives channel removal and parent exit until its
  observed descendants exit. A termination timeout retains exclusion.
- Coordination directories reached through a link or Windows junction are
  rejected. A dead owner's metadata does not prevent reacquiring the OS lock.

The process-tree observer tracks plugin-owned processes through Java process
handles. It does not control unrelated external CLIs or guarantee observation
of an arbitrary child that forks and reparents between OS snapshots. External
CLI detection and the Windows-only relocation policy belong to the next patch.

## Validation

Run from the repository root:

```powershell
.\gradlew.bat test --tests '*AiDataProcessGateTest' `
  --tests '*AiDataRuntimeQuiescenceTest' --tests '*ManagedProcessTreeTest' `
  --tests '*AiDataMigrationCoordinatorTest' --tests '*AiDataMigrationCrossJvmTest' `
  checkstyleMain -PskipWebview=true --offline --console=plain --max-workers=2
```

On Windows, all 18 cases and Checkstyle pass. The tests include actual Java
parent/child processes, two JVMs competing at a barrier, forced owner death,
shared-lock exclusion and a junction path guard. The relocation patch adds the
real daemon, recovery and cleanup entry-point regressions.
