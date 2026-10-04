# ClawBot terminal reply investigation

Date: 2026-10-04

## Evidence

The reported Codex session is `01a1052c-1bc0-7a10-8d93-60a0e80c8f2c`.
Its latest turn contains the final assistant message at 14:45:25.968 and
`task_complete` at 14:45:26.058 (Asia/Shanghai).

For the corresponding inbound request, the local outbound receipt store contains
the start notification and two progress notifications (14:44:05, 14:44:20 and
14:45:21), but no receipt for its deterministic terminal event ID. Both execution
journals were subsequently empty. This is consistent with terminal queue
acknowledgment without a channel send.

Disassembly of the installed plugin JAR confirms that it contains the faulty
terminal-reply sequence described below. The current compiled session registry
already contains the previous target-refresh fix. Compilation and commit times
alone do not establish that the IDE was running an older implementation.

## Confirmed defect

Both `sessionReplyResponse` and `sessionControlReplyResponse` insert `PENDING`
into `outboundEventStates` before calling `sendChannelText`.

`sendChannelText` interprets an existing `PENDING` state as an already-running
send and immediately returns an empty result. It never calls the daemon's
`send_text` method and never starts a durable outbound receipt. The caller then
acknowledges the queue and reports `SENT`. The IDE clears its execution guard,
so there is no subsequent terminal retry. Progress and command-list replies do
not perform the erroneous insertion, explaining why those messages arrive.

If the transport is initially unavailable, the premature state instead survives
the failed attempt. Subsequent terminal requests remain `PENDING`, preventing
recovery even after the transport becomes available.

This is a deterministic defect in the installed gateway. The historical logs do
not capture the particular terminal IPC response, so the historical attribution
combines the installed code, receipt evidence and deterministic reproduction.

## Repair plan and scope

1. Remove the two premature state insertions. Let `sendChannelText` own outbound
   state and receipt transitions.
2. Retain the existing terminal/control in-flight claims for concurrent requests.
   Retain sender authorization, connection ownership, dispatched-message checks,
   stable event IDs and ambiguity handling.
3. Exercise real authenticated local IPC, the daemon request/response framing and
   receipt persistence with an in-memory transport. Assert an actual send before
   accepting success, plus duplicate suppression, target refresh, definite
   failure/retry, unavailable-transport recovery, concurrent requests, ambiguous
   outcomes and revoked sender access. Cover both ordinary and control replies.
4. Run Java tests with test instrumentation enabled and verify the new test names
   in the generated report. Build the plugin for installation and a fresh manual
   WeChat turn.

Production scope is two removed lines in `ClawBotGatewayRuntimeService.java`.
The Codex adapter, session registry and Webview do not require another change for
this defect.

## Validation

With the original production implementation and current instrumented tests,
the gateway suite runs 21 tests and fails five of the six new regressions.
The direct delivery assertion reports that the expected second send never
occurred after the first progress send. Authorization rejection still passes.

An earlier invocation using `-x instrumentTestCode` ran only 15 old gateway
tests from cached instrumented classes, despite compiling the new source. That
invocation is not evidence for the new regressions. Do not use this exclusion to
validate changed tests without explicitly replacing the test classpath.

After removing the two insertions, the focused run passes all 149 ClawBot package
and UI tests, with zero failures/skips; all six new regression methods appear in
the XML report. `checkstyleMain` also passes. This run uses `instrumentTestCode`;
only the unrelated Webview build is excluded for the focused test stage.

Full verification: `gradlew.bat test checkstyleMain buildPlugin --offline` succeeds
with no test task exclusions. The XML reports contain 1,692 tests: 1,681 passed,
11 skipped, zero failures/errors. The Webview production build and plugin
packaging also succeed. `git diff --check` passes.

Artifact: `build/distributions/idea-claude-code-gui-0.5.9.zip`. The packaged gateway
class is byte-for-byte identical to the tested compiled class. No installed
plugin files were overwritten and the IDE was not restarted. No commit or push
was performed.

## Runtime acceptance boundary

The recording transport never starts Node or contacts WeChat. Automated results
verify local delivery orchestration and durable receipts, not external account
acceptance. Install the newly built plugin and restart IDEA before a fresh
WeChat task. The already acknowledged historical request cannot be automatically
reconstructed from journals that deliberately do not retain message contents.
