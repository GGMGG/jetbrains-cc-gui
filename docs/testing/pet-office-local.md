# Local Pet Office

This first Office slice depends on the AI data relocation foundation and session
execution ownership. It adds a standalone local window, sampled session desks,
project filtering, bounded floors, and activation of existing chat tabs.

Opening the Office neither initializes an AI provider nor starts a conversation.
Its independent HTML bundle does not load the chat application. The native bridge
allows only metadata reads and exact tab activation. It accepts no filesystem path,
model command, prompt, or caller-selected tab index.

The local authority samples atomic backend bindings. UI loading flags, transcript
IDs and heartbeats cannot prove execution. Recent results represent observed
terminal states, bounded to 50 items and ten minutes; sampling is not an event log.
Replacement, detached work, closed tabs, unknown ownership and terminal grace are
represented explicitly. No message text or provider credentials enter snapshots.

Activation checks source identity, binding epoch, route generation, pending
interaction, page identity and a three-second deadline. The EDT checks the live tab
again before selection and focus. Closing a page invalidates queued actions. The
page retains its last complete sample after transport failure and disables desk
activation until a valid refresh arrives.

Validation includes registry/ownership/navigation tests, strict JSON and snapshot
contracts, JCEF wrapper navigation, frontend transport timeout/abort/late reply
cases, floor pagination, and independent bundle packaging. This slice targets a
Windows local JCEF renderer. Actual IDE focus behavior remains a manual check.

Manual checks:

1. Open several projects and chat tabs, including two tabs for one transcript.
2. Open Office from Pet settings while the floating pet is disabled. Confirm no AI
   turn starts and all local tabs retain separate desks.
3. Run a turn, a question, and a permission wait in a background tab. Check phases
   and select the matching desk; verify the original tab activates.
4. Replace or close a tab while activation is queued. Confirm another tab is never
   substituted and a stale interaction is refused.
5. Close/reopen Office repeatedly. Verify the old page cannot send actions and the
   main chat bootstrap/assets still load.

Desktop preferences, cross-IDE presence, authenticated IPC, remote navigation,
custom assets, cache limits and theme scenery are subsequent slices.
