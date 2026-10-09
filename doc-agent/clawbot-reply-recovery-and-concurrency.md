# ClawBot reply recovery and gateway concurrency

Date: 2026-10-09

## Resulting behavior

The settings page can list up to eight recent final replies whose delivery was
unconfirmed or rejected. Complete replies are retained in encrypted storage for
24 hours, subject to the existing bounded storage capacity. Loading the list
returns delivery metadata, not answer bodies.

Resending requires an explicit confirmation. The gateway validates the binding
revision, sender authorization, transport and original session identity before
claiming recovery and again before delivery. A durable claim prevents repeated
confirmation from enqueueing the answer twice. Recovery sends the retained
answer without submitting another AI task; an unknown original delivery remains
unknown because the remote recipient may already have received it.

Expired replies, changed bindings, revoked senders, stale targets and unavailable
recovery state cannot be resent. Revocation and unbinding remove retained answer
bodies. The settings card distinguishes an empty list from unavailable recovery,
uses the surrounding settings typography and spacing, and fits narrow viewports.

## Gateway locking and UI responsiveness

Inbound command routing previously acquired the router lock before the gateway
lock, while IPC session-preview polling acquired them in the opposite order.
That cycle could block command processing and status reads. A synchronous JCEF
status read could then hold the message-dispatch gate and stall the IDE UI.

Inbound routing now acquires the gateway lock before entering the router and
checks the transport generation inside the same critical section. Network
polling remains outside that section. Status queries run on a background
executor; publishing their result retains browser and disposal checks.

Regression tests exercise ordinary messages and commands while session preview,
status and delivery operations run concurrently. They also verify asynchronous
status handling, recovery admission and persistence, duplicate suppression,
revocation, binding changes and the explicit confirmation flow.

## Validation boundary

Validation on the PR branch:

- 208 ClawBot Java tests pass with test instrumentation enabled, including all
  48 gateway tests, three reply-store tests and six status-handler tests.
  `checkstyleMain` passes.
- All 21 ClawBot settings tests pass. The Webview production build and test
  TypeScript check pass.
- Eight recovery browser cases pass using Microsoft Edge with one worker across
  desktop, narrow, short and mobile viewports. An initial four-worker run timed
  out during cold page startup; the serial run completes every case.
- The full Windows Java suite runs 1,661 tests: 1,650 pass, 10 skip, and the
  untouched history-index test
  `replacementWithPreservedSizeAndMtimeRebuildsIndex` fails.
- Full Webview checks expose failures in the unchanged Markdown copy and chat
  input callback tests. Both failures also reproduce in an isolated worktree at
  the previous PR head, `72423411`, before the ClawBot update.

The full repository test suites are therefore not reported as passing. These
results distinguish the current ClawBot regressions from the separate history
and chat-component failures.

Automated transport tests use an in-memory daemon and local authenticated IPC.
Browser tests use a simulated Java bridge. These checks cover gateway behavior
and UI confirmation; they do not establish real WeChat delivery or IDE runtime
responsiveness.

Before closing the freeze investigation, install the updated plugin in both
IDEs, restart them, and verify command batches, normal message delivery and
leader/follower switching without a UI stall. Earlier manual acceptance results
remain historical evidence, rather than a substitute for this post-fix check.
