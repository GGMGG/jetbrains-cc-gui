package com.github.claudecodegui.clawbot;

import com.github.claudecodegui.session.ClaudeSession.Message;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.CallbackHandler;
import com.github.claudecodegui.session.CodexMessageHandler;
import com.github.claudecodegui.provider.common.SDKResult;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ClawBotConversationPreviewTest {

    @Test
    public void formatsCorrelatedCodexAnswersWithoutShowingTheTransportEnvelope() {
        Message question = new Message(Message.Type.ASSISTANT, "Tool: codex_user_input", JsonParser.parseString("""
                {"codexThreadId":"thread","message":{"content":[{"type":"tool_use","name":"codex_user_input","id":"question",
                "input":{"questions":[{"id":"question-1","question":"Pick one."}]}}]}}
                """).getAsJsonObject());
        Message answer = new Message(Message.Type.USER, "Answers to your questions (request: question):\n\nPick one.\nB",
                JsonParser.parseString("{\"codexThreadId\":\"thread\",\"codexItemType\":\"userMessage\"}").getAsJsonObject());
        String preview = ClawBotConversationPreview.format(List.of(question, answer), false);
        assertTrue(preview.contains("用户回答：\nPick one.\nB"));
        assertFalse(preview.contains("Answers to your questions"));
        assertFalse(preview.contains("request:"));
        answer.raw.addProperty("codexThreadId", "another-thread");
        assertTrue(ClawBotConversationPreview.format(List.of(question, answer), false).contains("Answers to your questions"));
        assertTrue(ClawBotConversationPreview.format(List.of(answer), false).contains("Answers to your questions"));
    }

    @Test
    public void latePreviousCompletionDoesNotReportActiveTurnAsIdle() {
        ClaudeSession session = new ClaudeSession(null, null, null, null);
        session.getState().beginTurn();
        CodexMessageHandler previous = new CodexMessageHandler(session.getState(), new CallbackHandler());
        previous.onMessage("stream_start", "");
        previous.onMessage("stream_end", "");

        session.getState().beginTurn();
        session.getState().addMessage(new Message(Message.Type.USER, "current question"));
        CodexMessageHandler current = new CodexMessageHandler(session.getState(), new CallbackHandler());
        current.onMessage("stream_start", "");
        current.onMessage("content_delta", "working on it");
        previous.onComplete(new SDKResult());

        String preview = ClawBotConversationPreview.capture(session);
        assertTrue(preview.contains("处理中"));
        assertTrue(preview.contains("当前回复片段"));
        assertFalse(preview.contains("当前空闲"));

        current.onMessage("stream_end", "");
        assertTrue(ClawBotConversationPreview.capture(session).contains("当前空闲"));
    }

    @Test
    public void pendingQuestionIsNeverPairedWithPreviousAnswer() {
        String result = ClawBotConversationPreview.format(List.of(
                new Message(Message.Type.USER, "old question"),
                new Message(Message.Type.ASSISTANT, "old answer"),
                new Message(Message.Type.USER, "current question")), true);
        assertTrue(result.contains("current question"));
        assertTrue(result.contains("处理中"));
        assertTrue(result.contains("暂无可预览的回复"));
        assertFalse(result.contains("old answer"));
    }

    @Test
    public void includesLatestPublicAnswerAndSkipsToolResult() {
        Message tool = new Message(Message.Type.USER, "[tool_result]",
                JsonParser.parseString("{\"content\":[{\"type\":\"tool_result\",\"content\":\"secret\"}]}").getAsJsonObject());
        String result = ClawBotConversationPreview.format(List.of(
                new Message(Message.Type.USER, "question"), tool,
                new Message(Message.Type.ASSISTANT, "answer")), false);
        assertTrue(result.contains("question"));
        assertTrue(result.contains("answer"));
        assertTrue(result.contains("当前空闲"));
        assertFalse(result.contains("secret"));
        assertFalse(result.contains("tool_result"));
    }

    @Test
    public void neverIncludesThinkingBlocksOrSystemMessages() {
        Message assistant = new Message(Message.Type.ASSISTANT, "private reasoning",
                JsonParser.parseString("{\"message\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"private reasoning\"},"
                        + "{\"type\":\"text\",\"text\":\"public answer\"}]}}").getAsJsonObject());
        String result = ClawBotConversationPreview.format(List.of(
                new Message(Message.Type.SYSTEM, "private system instructions"),
                new Message(Message.Type.USER, "question"), assistant), false);
        assertTrue(result.contains("public answer"));
        assertFalse(result.contains("private"));
    }

    @Test
    public void emptyConversationDoesNotInventAQuestion() {
        assertTrue(ClawBotConversationPreview.format(List.of(), false).contains("暂无可预览"));
        assertTrue(ClawBotConversationPreview.format(List.of(new Message(Message.Type.ASSISTANT, "orphan answer")), false)
                .contains("暂无可预览"));
    }

    @Test
    public void restoredSessionReadsRecentHistoryWithoutChangingLiveMessagesOrState() {
        ClaudeSession session = restored(cancellation -> List.of(new Message(Message.Type.USER, "persisted question"),
                new Message(Message.Type.ASSISTANT, "persisted answer")));
        String preview = ClawBotConversationPreview.capture(session);
        assertTrue(preview.contains("persisted question"));
        assertTrue(preview.contains("persisted answer"));
        assertTrue(session.getMessages().isEmpty());
        assertFalse(session.isLoading());
        assertFalse(session.isBusy());
        assertEquals("stored-session", session.getSessionId());
        assertFalse(preview.contains("未读取磁盘历史"));
    }

    @Test
    public void currentQuestionNeverLoadsOrBorrowsAnOlderPersistedAnswer() {
        ClaudeSession session = restored(cancellation -> { throw new AssertionError("History must not be read"); });
        session.getState().addMessage(new Message(Message.Type.USER, "current question"));
        session.getState().setBusy(true);
        String preview = ClawBotConversationPreview.capture(session);
        assertTrue(preview.contains("current question"));
        assertTrue(preview.contains("暂无可预览的回复"));
        assertTrue(preview.contains("处理中"));
    }

    @Test
    public void recentQuestionSurvivesMoreThan256ToolRecords() {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Message.Type.USER, "long running question"));
        for (int index = 0; index < 300; index++) {
            messages.add(new Message(Message.Type.USER, "[tool_result]"));
        }
        messages.add(new Message(Message.Type.ASSISTANT, "latest public answer"));
        String preview = ClawBotConversationPreview.format(messages, false);
        assertTrue(preview.contains("long running question"));
        assertTrue(preview.contains("latest public answer"));
        assertFalse(preview.contains("tool_result"));
    }

    @Test
    public void historyReadRunsOutsideMessageLockAndNewLiveTurnWins() {
        ClaudeSession[] holder = new ClaudeSession[1];
        holder[0] = restored(cancellation -> {
            ClaudeSession current = holder[0];
            assertFalse(Thread.holdsLock(current.getState().getMessageStateLock()));
            current.getState().addMessage(new Message(Message.Type.USER, "new live question"));
            current.getState().setBusy(true);
            return List.of(new Message(Message.Type.USER, "old disk question"), new Message(Message.Type.ASSISTANT, "old disk answer"));
        });
        String preview = ClawBotConversationPreview.capture(holder[0]);
        assertTrue(preview.contains("new live question"));
        assertFalse(preview.contains("old disk"));
    }

    @Test
    public void failedReadDoesNotMasqueradeAsEmptyHistoryOrExposeItsError() {
        ClaudeSession session = restored(cancellation -> { throw new IllegalStateException("private path or provider error"); });
        String preview = ClawBotConversationPreview.capture(session);
        assertTrue(preview.contains("暂时无法读取"));
        assertFalse(preview.contains("private"));
        assertFalse(preview.contains("暂无可预览的一问一答"));
    }

    @Test
    public void changedTargetDiscardsTheOldHistory() {
        ClaudeSession[] holder = new ClaudeSession[1];
        holder[0] = restored(cancellation -> {
            holder[0].getState().setSessionId("another-session");
            return List.of(new Message(Message.Type.USER, "stale question"), new Message(Message.Type.ASSISTANT, "stale answer"));
        });
        assertThrows(CancellationException.class, () -> ClawBotConversationPreview.capture(holder[0]));
    }

    @Test
    public void cancelledSelectionDoesNotStartHistoryRead() {
        ClaudeSession session = restored(cancellation -> { throw new AssertionError("History must not be read"); });
        assertThrows(CancellationException.class, () -> ClawBotConversationPreview.capture(session, () -> true));
    }

    @Test
    public void historyAnswerIsNotPresentedAsTheCurrentlyRunningReply() {
        ClaudeSession session = restored(cancellation -> List.of(new Message(Message.Type.USER, "previous question"),
                new Message(Message.Type.ASSISTANT, "previous answer")));
        session.getState().setBusy(true);
        String preview = ClawBotConversationPreview.capture(session);
        assertTrue(preview.contains("处理中"));
        assertTrue(preview.contains("最近回复：\nprevious answer"));
        assertFalse(preview.contains("当前回复片段"));
    }

    @Test(timeout = 5000)
    public void slowHistoryReadHonorsThePreviewDeadline() {
        ClaudeSession session = restored(cancellation -> {
            while (!cancellation.getAsBoolean()) {
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
            }
            throw new CancellationException("Preview read deadline");
        });
        String preview = ClawBotConversationPreview.capture(session);
        assertTrue(preview.contains("暂时无法读取"));
        assertTrue(session.getMessages().isEmpty());
    }

    private static ClaudeSession restored(Function<BooleanSupplier, List<Message>> history) {
        ClaudeSession session = new ClaudeSession(null, null, null, null) {
            @Override
            public List<Message> readRecentHistoryMessages(BooleanSupplier cancellation) {
                return history.apply(cancellation);
            }
        };
        session.getState().setSessionId("stored-session");
        return session;
    }

    @Test
    public void boundsQuestionAndAnswerWithoutSplittingUnicodeOrLeakingControls() {
        String result = ClawBotConversationPreview.format(List.of(
                new Message(Message.Type.USER, "q".repeat(799) + "😀tail"),
                new Message(Message.Type.ASSISTANT, "answer\u0000" + "a".repeat(5000))), false);
        assertTrue(result.length() < 1800);
        assertFalse(result.contains("\u0000"));
        assertFalse(result.contains("\uD83D"));
        assertTrue(result.contains("…"));
    }
}
