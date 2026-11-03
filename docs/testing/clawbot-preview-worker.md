# Selected-session preview worker

Session selection requests a read-only preview using the progress contract.
Capture runs on the application executor, outside the scheduled inbound poller,
with at most one preview active for this window. A pending history read therefore
does not block incoming messages or control actions on that poller.

The callback checks disposal, project lifecycle, session identity, client
identity, runtime generation and provider before replying. The same checks cancel
the cooperative history read, whose progress-layer deadline is 2.5 seconds.
Closing a window also retains both ClawBot cleanup and history-load cancellation.

For installed IDE/WeChat acceptance, select a restored session whose recent Q&A
is only on disk, confirm its public question/answer is shown while idle, then
repeat while changing sessions/providers or closing the window. No obsolete
preview should be delivered and normal polling must remain responsive. This
manual transport/JCEF check is separate from the automated fixture tests.
