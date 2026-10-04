package com.github.claudecodegui.clawbot;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.ClaudeSession.Message;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClawBotProgressTrackerTest {
    private static final long FIRST_CHECK = TimeUnit.SECONDS.toNanos(15);
    private static final long TEXT_INTERVAL = TimeUnit.MINUTES.toNanos(1);

    @Test
    public void sendsOnlyNewPublicAssistantTextFromCurrentTurn() {
        Fixture fixture = startTurn();
        Message answer = new Message(Message.Type.ASSISTANT, "first chunk");
        fixture.session().getState().addMessage(answer);

        ClawBotProgressTracker.Notification first = fixture.tracker().prepare("RUNNING", "", FIRST_CHECK);

        assertNotNull(first);
        assertTrue(first.text().contains("first chunk"));
        assertFalse(first.reminder());
        fixture.tracker().finish(first, true, FIRST_CHECK);

        answer.content = "first chunk and second chunk";
        ClawBotProgressTracker.Notification second = fixture.tracker().prepare(
                "RUNNING", "", FIRST_CHECK + TEXT_INTERVAL);

        assertNotNull(second);
        assertTrue(second.text().contains("second chunk"));
        assertFalse(second.text().contains("first chunk"));
        assertFalse(second.reminder());
    }

    @Test
    public void excludesThinkingBlocksFromProgressText() {
        Fixture fixture = startTurn();
        Message answer = new Message(Message.Type.ASSISTANT, "private fallback text",
                JsonParser.parseString("{\"message\":{\"content\":["
                        + "{\"type\":\"thinking\",\"thinking\":\"private reasoning\"},"
                        + "{\"type\":\"text\",\"text\":\"public progress\"}]}}").getAsJsonObject());
        fixture.session().getState().addMessage(answer);

        ClawBotProgressTracker.Notification notification = fixture.tracker().prepare(
                "RUNNING", "", FIRST_CHECK);

        assertNotNull(notification);
        assertTrue(notification.text().contains("public progress"));
        assertFalse(notification.text().contains("private"));
    }

    @Test
    public void retriesTheSameNotificationAfterFailedDelivery() {
        Fixture fixture = startTurn();
        fixture.session().getState().addMessage(new Message(Message.Type.ASSISTANT, "in progress"));
        ClawBotProgressTracker.Notification notification = fixture.tracker().prepare(
                "RUNNING", "", FIRST_CHECK);
        assertNotNull(notification);

        fixture.tracker().finish(notification, false, FIRST_CHECK);
        assertNull(fixture.tracker().prepare("RUNNING", "", FIRST_CHECK + TimeUnit.MILLISECONDS.toNanos(999)));
        ClawBotProgressTracker.Notification retry = fixture.tracker().prepare(
                "RUNNING", "", FIRST_CHECK + TimeUnit.SECONDS.toNanos(1));

        assertSame(notification, retry);
        fixture.tracker().finish(retry, true, FIRST_CHECK + TimeUnit.SECONDS.toNanos(1));
        assertEquals(1L, retry.sequence());
    }

    @Test
    public void doesNotReadPreviousTurnAfterAnotherTurnStarts() {
        Fixture fixture = startTurn();
        fixture.session().getState().addMessage(new Message(Message.Type.ASSISTANT, "first turn response"));
        fixture.session().getState().beginTurn();
        fixture.session().getState().addMessage(new Message(Message.Type.USER, "second question"));

        assertNull(fixture.tracker().prepare("RUNNING", "", FIRST_CHECK));
    }

    @Test
    public void capsAllProgressNotificationsAtTwelvePerTurn() {
        Fixture fixture = startTurn();
        Message answer = new Message(Message.Type.ASSISTANT, "progress 0");
        fixture.session().getState().addMessage(answer);
        long now = FIRST_CHECK;

        for (int index = 0; index < 12; index++) {
            answer.content = "progress " + index;
            ClawBotProgressTracker.Notification notification = fixture.tracker().prepare("RUNNING", "", now);
            assertNotNull(notification);
            fixture.tracker().finish(notification, true, now);
            now += TEXT_INTERVAL;
        }

        answer.content = "progress after limit";
        assertNull(fixture.tracker().prepare("RUNNING", "", now));
    }

    private static Fixture startTurn() {
        ClaudeSession session = new ClaudeSession(null, null, null, null);
        session.getState().beginTurn();
        session.getState().addMessage(new Message(Message.Type.USER, "question"));
        ClawBotProgressTracker tracker = new ClawBotProgressTracker(session, 0, 0L);
        tracker.bind();
        return new Fixture(session, tracker);
    }

    private record Fixture(ClaudeSession session, ClawBotProgressTracker tracker) {
    }
}
