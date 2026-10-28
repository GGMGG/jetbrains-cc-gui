package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SessionRecentHistoryTest {
    @Test
    public void claudeReadsOnlyTheLatestPageAndLeavesTheLiveTurnUntouched() {
        AtomicInteger reads = new AtomicInteger();
        ClaudeSDKBridge bridge = new ClaudeSDKBridge() {
            @Override
            public JsonObject getSessionMessagesPage(String id, String cwd, Integer before, int limit, BooleanSupplier cancellation) {
                assertEquals("persisted", id);
                assertEquals("/workspace", cwd);
                assertEquals(null, before);
                assertEquals(1, limit);
                reads.incrementAndGet();
                return JsonParser.parseString("""
                        {"messages":[{"type":"user","content":"recent question"},
                          {"type":"assistant","content":"recent answer"},
                          {"type":"user","isSidechain":true,"content":"subagent text"}]}
                        """).getAsJsonObject();
            }

            @Override
            public List<JsonObject> getSessionMessages(String id, String cwd, BooleanSupplier cancellation) {
                throw new AssertionError("A recent preview must not load every Claude page");
            }
        };
        ClaudeSession session = new ClaudeSession(null, bridge, null, null);
        session.getState().setSessionId("persisted");
        session.getState().setCwd("/workspace");
        session.getState().setBusy(true);
        String epoch = session.getRuntimeSessionEpoch();
        List<ClaudeSession.Message> messages = session.readRecentHistoryMessages(() -> false);
        assertEquals(1, reads.get());
        assertEquals(2, messages.size());
        assertEquals("recent question", messages.get(0).content);
        assertEquals("recent answer", messages.get(1).content);
        assertTrue(session.getMessages().isEmpty());
        assertTrue(session.isBusy());
        assertFalse(session.isLoading());
        assertEquals(epoch, session.getRuntimeSessionEpoch());
    }

    @Test
    public void codexPreviewUsesTheCurrentThreadAndHostWithoutLoadingAllHistory() {
        CodexSDKBridge bridge = new CodexSDKBridge() {
            @Override
            public List<JsonObject> getRecentSessionMessages(String id, String cwd, String channel, BooleanSupplier cancellation) {
                assertEquals("codex-thread", id);
                assertEquals("codex-host", channel);
                return List.of(JsonParser.parseString("{\"type\":\"user\",\"content\":\"question\"}").getAsJsonObject());
            }
        };
        ClaudeSession session = new ClaudeSession(null, null, bridge, null);
        session.setProvider("codex");
        session.getState().setSessionId("codex-thread");
        session.getState().setChannelId("codex-host");
        assertEquals("question", session.readRecentHistoryMessages(() -> false).get(0).content);
        assertTrue(session.getMessages().isEmpty());
        assertEquals("codex-host", session.getChannelId());
    }

    @Test
    public void cancelledAndUnpersistedSessionsDoNotStartAHistoryRead() {
        ClaudeSession session = new ClaudeSession(null, null, null, null);
        assertTrue(session.readRecentHistoryMessages(() -> false).isEmpty());
        session.getState().setSessionId("persisted");
        assertThrows(CancellationException.class, () -> session.readRecentHistoryMessages(() -> true));
    }
}
