# ClawBot interaction and progress review

Date: 2026-10-04

Scope: analysis only. No production or test code changes and no remote actions.

## Findings

### Questions are not connected to the remote control path

`ClaudeChatWindow.formatClawBotProgress` (line 3288) renders fixed IDE-only
messages for `WAITING_USER` and `WAITING_PLAN_APPROVAL`. The pending question
content, options and response handle never reach ClawBot through this path.

`PermissionHandler.showAskUserQuestionDialog` (line 813) holds a pending future,
request ID and dialog token, and delivers the questions to the Webview. Only
the Webview response handler currently completes that interaction from user
input. The same pattern exists for plan approval (line 927).

`ClawBotInboundAction` has no answer action. `ClawBotMessageRouter.handle` routes
ordinary text to the normal message queue. `ClaudeChatWindow.pollClawBotInbound`
does not consume another ordinary message while the current remote turn is
active. Sending an answer as a new chat message therefore cannot resolve the
pending question and can leave the reply behind the task that needs it.

This is a missing interaction route, rather than a WeChat text transport limit.

### Interaction inventory

| Interaction | Existing path | Current ClawBot behavior | Proposed treatment |
| --- | --- | --- | --- |
| Claude AskUserQuestion | Permission file IPC -> PermissionHandler question future | IDE-only notice | Forward questions; accept single choice, multiple choice and free text |
| Codex native requestUserInput and CC GUI dynamic question tool | Codex reverse request -> question file IPC | Same IDE-only notice | Reuse the same answer future and provider answer mapping |
| Codex request_user_input_async | Event bridge -> question file IPC -> turn/steer or queued input | Same notice; blocking semantics are not preserved | Forward question; distinguish pending input from actual provider suspension |
| Plan approval, including Claude ExitPlanMode | Plan file IPC -> plan approval future | IDE-only notice | Present plan and explicit decisions; preserve target permission mode |
| Session PermissionManager tool approval | PermissionRequest -> ClawBot approval token | Already supports /approve and /deny for the requesting sender and turn | Retain and integrate with common interaction state |
| File IPC permission dialog, including Codex command/file approval fallback | PermissionService -> showFrontendPermissionDialog | No equivalent remote token path; wait detection omits this map | Add wait visibility and explicit one-time decision routing |
| File diff review or native system permission fallback | PermissionService.tryDiffReview / dispatchPermissionFallback | Local IDE interaction | Report the exact wait reason; retain IDE handling unless full review context and equivalent decisions can be presented remotely |

This inventory covers task question/approval paths relevant to ClawBot. It is
not a claim that login, file pickers or every IDE modal can be answered remotely.

### Wait detection is incomplete and presentation resumes too early

`PermissionHandler.getClawBotPendingInteractionPhase` (line 268) checks only
question and plan maps. It omits `pendingPermissionRequests`, populated by
`showFrontendPermissionDialog` (line 540). `ClaudeChatWindow.clawBotProgressPhase`
(line 3297) additionally checks the session PermissionManager, but that does not
cover the separate file IPC future or the diff review future. These waits can
be presented as `RUNNING`.

The question handler removes a pending entry before completing its future
(lines 893-918). ClawBot can observe `RUNNING` before the provider consumes the
answer. There is no explicit answer-received or resumed notification.

`permission-ipc.js` (lines 130-141) writes the question tool name as
`AskUserQuestion` regardless of the incoming `toolName`. In particular, the
`request_user_input_async` origin is lost here. A pending dialog alone is not
proof that the Codex process has stopped: its asynchronous tool returns
`accepted: true`, while the local event bridge awaits the question response
(`codex-event-handler.js`, line 185). Local event forwarding and provider
execution must not be treated as the same state.

### Progress already suppresses text for observed waits, but has gaps

`ClawBotProgressTracker.prepare` (line 72) emits text excerpts only for
`RUNNING`. Observed waits get a status reminder immediately and then every ten
minutes. It does not intentionally emit processing excerpts throughout a
stable, correctly recognized `WAITING_USER` interval.

There are nevertheless concrete gaps:

1. `prepare` returns when `inFlight` is true before applying a phase change.
   `dispatch` checks turn ownership and closure, not the current interaction
   phase. An excerpt prepared while running can still be sent after a question
   opens. This is a static race finding, not a proven attribution of the screenshot.
2. When the pending map empties, `prepare` resets its next check to now and
   compares against the last delivered text. Undelivered text from before the
   question can immediately appear as a new processing excerpt.
3. There is no resume notice, so valid progress after answering still looks like
   a continuation of the previously announced wait.
4. Wait notices and progress share the twelve-notification cap. A question that
   opens after that quota can receive no wait notice at all. Actionable questions
   must not depend on the progress quota.

## Screenshot evidence and limits

The matching local Codex session is
`01a105ac-b779-7210-97b5-ef1be5bae0d1`. Its records show, in Asia/Shanghai time:

- 17:26:04: assistant reports the local commit and missing PR.
- 17:26:05: `request_user_input_async` asks for the PR reference.
- 17:26:06: the question tool returns `accepted: true`.
- 17:27:08: a `user_input_response` supplies the user's local-worktree-only answer.
- 17:27:20: assistant acknowledges that answer and continues validation.
- 17:29:02: assistant reports test progress.
- 17:29:44: final answer and task completion.

Available outbound receipts in this time window include a send at 17:27:04,
before the provider records the answer at 17:27:08. Receipts do not retain
message bodies or the IDE dialog transition timestamp, so they alone cannot
prove the first post-notice excerpt was sent while the IDE dialog was still
pending. IDE response submission and provider answer consumption are separate
events. Later progress and the final answer are consistent with the answered
task continuing normally. Do not characterize every post-notice message as an
illegal paused-state send.

## Proposed implementation sequence

1. Represent an interaction as structured data owned by the existing task:
   request ID, dialog token, type/source, session/turn owner, runtime generation,
   originating sender, questions/options or plan/operation context, deadline and
   resolution state. Preserve the original provider/tool type through file IPC.
   Preserve current ownership, authorization and expiry checks.
2. Add an answer path through the control channel, which remains polled during
   an active task. For one unambiguous question belonging to the current sender
   and selected task, accept a number, option label or free text directly. For
   multiple questions use sequential prompts or explicit question IDs. Provide
   an explicit answer command for ambiguity. Do not interpret answer text such
   as "choose 2" as session navigation, and do not enqueue an answer as a new
   provider turn. Keep explicit session/stop commands unambiguous.
3. Resolve the same pending future from either IDE or WeChat through a single
   validated completion operation. First valid resolution wins; close/invalidate
   the other surface. Duplicate, expired, revoked or wrong-turn answers must not
   resume anything. Preserve multi-select arrays, option validation and free text
   semantics; send provider-specific output through the existing adapter.
4. Track waiting, answer submission, resumed work and terminal outcomes explicitly.
   While an actionable question is pending, suppress automatic text/idle progress
   by default, including optional async questions as a notification policy.
   This must not claim to interrupt the underlying asynchronous provider.
   Immediately show the question once. On accepted resolution, send a clear
   answer-received/continue notification before normal progress resumes. Do not
   replay pre-question excerpts as fresh progress. Explain cancellation/timeout;
   never infer approval from silence.
5. Invalidate queued excerpts on interaction revision changes and recheck before
   transport submission. Preserve terminal ordering. Already-submitted network
   messages cannot be recalled. Essential interaction and completion messages
   must bypass the regular progress quota; preserve stable IDs and deduplication.
6. Keep final results, errors, cancellation and explicit status requests deliverable.
   Handle task completion while an optional async question is pending by resolving
   or expiring that interaction explicitly, without leaving a live stale question.
   Add metadata-only transition logs to make future ordering disputes diagnosable.

Recommended first delivery: ordinary questions plus wait/resume correctness,
including visibility of permission waits. Extend plan/permission remote decisions
after that path is verified. IDE diff review needs a separate equivalent review
design rather than treating arbitrary text as approval.

## Required verification before implementation is considered complete

- All question sources, single/multiple choice, free text and multiple questions.
- A response during an active task reaches its pending interaction, not the
  ordinary queued-message path; normal chat/navigation still works otherwise.
- No text/idle excerpt while waiting, including a prepared-but-not-sent excerpt,
  a delivery retry, multiple outstanding questions, and a question after twelve
  progress messages. Resume notification precedes fresh progress.
- IDE and WeChat racing, duplicate answers, invalid choices, timeout/cancellation,
  stop/new session, changed owner/generation, revoked sender and transport failure.
- Codex async versus synchronous behavior; question content/answer mapping remains
  correct through Java, Webview and Node IPC. Final results still arrive once.
- Targeted Java/bridge/Webview checks for changed components, followed by required
  Java tests and Checkstyle, then a fresh manual WeChat acceptance run.

Existing `ClawBotProgressTrackerTest` covers text deltas, private-block exclusion,
delivery retry, turn ownership and the progress cap. It has no wait/resume or
prepared-send-versus-question-transition regression. This review did not run new
tests or claim end-to-end validation of the proposed changes.
