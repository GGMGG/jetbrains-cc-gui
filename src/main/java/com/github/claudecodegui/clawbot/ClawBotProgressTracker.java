package com.github.claudecodegui.clawbot;

import com.github.claudecodegui.session.ClaudeSession;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Per-request progress cursor; transport I/O never holds the session message lock. */
public final class ClawBotProgressTracker {
    private static final long TEXT_INTERVAL = TimeUnit.MINUTES.toNanos(1);
    private static final long IDLE_INTERVAL = TimeUnit.MINUTES.toNanos(5);
    private static final long WAIT_INTERVAL = TimeUnit.MINUTES.toNanos(10);
    private static final int MAX_NOTIFICATIONS = 12;
    private static final int MAX_TEXT = 800;

    private final Object deliveryLock = new Object();
    private final ClaudeSession session;
    private final int firstMessageIndex;
    private Object turnOwner;
    private String runtimeEpoch;
    private ClaudeSession.Message question;
    private boolean closed;
    private boolean inFlight;
    private String phase = "RUNNING";
    private String phaseText = "";
    private long nextCheck;
    private long nextReminder;
    private int notificationCount;
    private long sequence;
    private Response delivered = new Response(null, "");
    private Notification pending;
    private Response pendingResponse;
    private int failures;
    private long retryAt;

    public ClawBotProgressTracker(ClaudeSession session, int firstMessageIndex, long now) {
        this.session = session;
        this.firstMessageIndex = firstMessageIndex;
        nextCheck = now + TimeUnit.SECONDS.toNanos(15);
        nextReminder = nextCheck;
    }

    /** Called after send synchronously publishes its user message and turn owner. */
    public void bind() {
        Object owner;
        String epoch;
        ClaudeSession.Message anchor;
        synchronized (session.getState().getMessageStateLock()) {
            List<ClaudeSession.Message> messages = session.getMessages();
            owner = session.getState().getTurnOwner();
            epoch = session.getRuntimeSessionEpoch();
            anchor = firstMessageIndex >= 0 && firstMessageIndex < messages.size()
                    ? messages.get(firstMessageIndex) : null;
            // A later local send must not be mistaken for this request's turn.
            for (int index = firstMessageIndex + 1; index < messages.size(); index++) {
                if (messages.get(index).type == ClaudeSession.Message.Type.USER
                        && !"[tool_result]".equals(messages.get(index).content)) {
                    anchor = null;
                    break;
                }
            }
        }
        synchronized (this) {
            turnOwner = owner;
            runtimeEpoch = epoch;
            question = anchor;
        }
    }

    public synchronized Notification prepare(String currentPhase, String statusText, long now) {
        if (closed || inFlight || question == null) {
            return null;
        }
        boolean phaseChanged = !phase.equals(currentPhase)
                || (!"RUNNING".equals(currentPhase) && !phaseText.equals(statusText));
        if (phaseChanged) {
            phase = currentPhase;
            phaseText = statusText;
            pending = null;
            pendingResponse = null;
            failures = 0;
            nextCheck = now;
            nextReminder = now;
        }
        if (pending != null) {
            if (!ClawBotDeliveryRetryPolicy.isDue(now, retryAt)) {
                return null;
            }
        } else if (!ClawBotDeliveryRetryPolicy.isDue(now, nextCheck)) {
            return null;
        }
        Response response = ClawBotConversationPreview.captureResponse(
                session, firstMessageIndex, turnOwner, runtimeEpoch, question);
        if (response == null) {
            closed = true;
            pending = null;
            pendingResponse = null;
            return null;
        }
        if (pending == null && notificationCount < MAX_NOTIFICATIONS) {
            boolean running = "RUNNING".equals(phase);
            nextCheck = now + (running ? TEXT_INTERVAL : WAIT_INTERVAL);
            if (running && !response.text().isBlank() && !response.equals(delivered)) {
                String text = response.text();
                if (response.message() == delivered.message() && text.startsWith(delivered.text())) {
                    text = text.substring(delivered.text().length());
                }
                pendingResponse = response;
                pending = new Notification(++sequence, "【处理中 · 最新回复】\n\n" + latestExcerpt(text), false);
            } else if (ClawBotDeliveryRetryPolicy.isDue(now, nextReminder)) {
                String text = running
                        ? (response.text().isBlank() ? "任务仍在处理中，暂未产生可展示的回复。"
                        : "任务仍在执行，暂无新的文本回复。") : statusText;
                pendingResponse = delivered;
                pending = new Notification(++sequence, text, true);
            }
        }
        inFlight = pending != null;
        return pending;
    }

    /** Serializes progress delivery with close, so terminal replies cannot be overtaken. */
    public void dispatch(Notification notification, BooleanSupplier current, Sender sender) {
        synchronized (deliveryLock) {
            try {
                synchronized (this) {
                    if (closed || pending != notification || !current.getAsBoolean()) {
                        return;
                    }
                }
                if (ClawBotConversationPreview.captureResponse(
                        session, firstMessageIndex, turnOwner, runtimeEpoch, question) == null) {
                    close();
                    return;
                }
                boolean accepted = sender.send(notification);
                finish(notification, accepted, System.nanoTime());
            } catch (IOException | RuntimeException error) {
                finish(notification, false, System.nanoTime());
            } finally {
                synchronized (this) {
                    inFlight = false;
                }
            }
        }
    }

    synchronized void finish(Notification notification, boolean accepted, long now) {
        if (closed || pending != notification) {
            return;
        }
        inFlight = false;
        if (accepted) {
            delivered = pendingResponse;
            notificationCount++;
            nextReminder = now + ("RUNNING".equals(phase) ? IDLE_INTERVAL : WAIT_INTERVAL);
            nextCheck = now + ("RUNNING".equals(phase) ? TEXT_INTERVAL : WAIT_INTERVAL);
            pending = null;
            pendingResponse = null;
            failures = 0;
        } else {
            failures = Math.min(failures + 1, 31);
            retryAt = now + ClawBotDeliveryRetryPolicy.delayNanos(failures);
        }
    }

    public void close() {
        synchronized (deliveryLock) {
            synchronized (this) {
                closed = true;
                pending = null;
                pendingResponse = null;
            }
        }
    }

    static String latestExcerpt(String text) {
        String visible = text.trim();
        if (visible.length() <= MAX_TEXT) {
            return visible;
        }
        int start = visible.length() - MAX_TEXT;
        if (Character.isLowSurrogate(visible.charAt(start))) {
            start++;
        }
        int paragraph = visible.indexOf('\n', start);
        if (paragraph >= start && paragraph < start + MAX_TEXT / 2) {
            start = paragraph + 1;
        }
        return "…（仅展示最新片段）\n" + visible.substring(start).trim();
    }

    record Response(Object message, String text) { }

    public record Notification(long sequence, String text, boolean reminder) { }

    @FunctionalInterface
    public interface Sender {
        boolean send(Notification notification) throws IOException;
    }
}
