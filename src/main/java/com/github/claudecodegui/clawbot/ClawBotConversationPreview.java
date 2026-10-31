package com.github.claudecodegui.clawbot;

import com.github.claudecodegui.session.ClaudeSession;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

public final class ClawBotConversationPreview {

    private static final int MAX_QUESTION_TEXT = 300;
    private static final int MAX_ANSWER_TEXT = 800;
    private static final Pattern CODEX_ANSWER = Pattern.compile("^Answers to your questions(?: \\(request: ([^\\r\\n]*)\\))?:\\n\\n");

    private ClawBotConversationPreview() {
    }

    public static String capture(ClaudeSession session) {
        return capture(session, () -> false);
    }

    /** Explicit selection can read recent history, independently of startup history restoration. */
    public static String capture(ClaudeSession session, BooleanSupplier cancellation) {
        String epoch = session.getRuntimeSessionEpoch();
        String sessionId = session.getSessionId();
        String provider = session.getProvider();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
        BooleanSupplier stale = () -> cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()
                || !Objects.equals(epoch, session.getRuntimeSessionEpoch()) || !Objects.equals(sessionId, session.getSessionId())
                || !Objects.equals(provider, session.getProvider());
        if (stale.getAsBoolean()) {
            throw new CancellationException("Preview target changed");
        }
        synchronized (session.getState().getMessageStateLock()) {
            Preview memory = latest(session.getMessages(), session.isBusy() || session.isLoading(), false);
            if (memory.hasQuestion() || session.getSessionId() == null || session.getSessionId().isBlank()) {
                return memory.text();
            }
        }
        List<ClaudeSession.Message> history = List.of();
        boolean unavailable = false;
        try {
            history = session.readRecentHistoryMessages(() -> stale.getAsBoolean() || System.nanoTime() >= deadline);
            unavailable = System.nanoTime() >= deadline;
        } catch (RuntimeException error) {
            unavailable = true;
        }
        synchronized (session.getState().getMessageStateLock()) {
            if (stale.getAsBoolean()) {
                throw new CancellationException("Preview target changed");
            }
            boolean busy = session.isBusy() || session.isLoading();
            Preview memory = latest(session.getMessages(), busy, false);
            if (memory.hasQuestion()) {
                return memory.text();
            }
            return unavailable ? "【会话进度】\n\n" + state(busy) + "\n\n最近对话暂时无法读取，请稍后重试。"
                    : latest(history, busy, true).text();
        }
    }

    /** Captures only public assistant text belonging to the originating turn. */
    static ClawBotProgressTracker.Response captureResponse(
            ClaudeSession session, int firstMessageIndex, Object turnOwner, String runtimeEpoch,
            ClaudeSession.Message question) {
        synchronized (session.getState().getMessageStateLock()) {
            if (!session.getState().isCurrentTurn(turnOwner)
                    || !runtimeEpoch.equals(session.getRuntimeSessionEpoch())) {
                return null;
            }
            List<ClaudeSession.Message> messages = session.getMessages();
            if (firstMessageIndex < 0 || firstMessageIndex >= messages.size()
                    || messages.get(firstMessageIndex) != question) {
                return null;
            }
            for (int index = messages.size() - 1; index > firstMessageIndex; index--) {
                ClaudeSession.Message message = messages.get(index);
                if (message.type == ClaudeSession.Message.Type.ASSISTANT) {
                    String text = visibleText(message, Integer.MAX_VALUE);
                    if (!text.isBlank()) {
                        return new ClawBotProgressTracker.Response(message, text);
                    }
                }
            }
            return new ClawBotProgressTracker.Response(null, "");
        }
    }

    static String format(List<ClaudeSession.Message> messages, boolean busy) {
        return latest(messages, busy, false).text();
    }

    private record Preview(boolean hasQuestion, String text) { }

    private static String state(boolean busy) {
        return busy ? "处理中，最终回复尚未完成。" : "当前空闲。";
    }

    private static Preview latest(List<ClaudeSession.Message> messages, boolean busy, boolean history) {
        String question = "";
        String answer = "";
        for (int index = messages.size() - 1; index >= 0; index--) {
            ClaudeSession.Message message = messages.get(index);
            if (message.type != ClaudeSession.Message.Type.USER && message.type != ClaudeSession.Message.Type.ASSISTANT) {
                continue;
            }
            int maxLength = message.type == ClaudeSession.Message.Type.USER
                    ? MAX_QUESTION_TEXT : MAX_ANSWER_TEXT;
            String visible = visibleText(message, maxLength);
            if (message.type == ClaudeSession.Message.Type.USER) {
                String reply = codexAnswer(messages, index);
                if (reply != null) {
                    visible = bounded("用户回答：\n" + reply, maxLength);
                }
            }
            if (message.type == ClaudeSession.Message.Type.USER && !visible.isBlank()) {
                question = visible;
                break;
            }
            if (message.type == ClaudeSession.Message.Type.ASSISTANT && answer.isEmpty() && !visible.isBlank()) {
                answer = visible;
            }
        }
        String state = state(busy);
        if (question.isEmpty()) {
            return new Preview(false, "【会话进度】\n\n" + state + "\n\n该会话暂无可预览的一问一答。");
        }
        return new Preview(true, "【会话进度】\n\n" + state + "\n\n最近提问：\n" + question + "\n\n"
                + (answer.isEmpty() ? "该问题暂无可预览的回复。" : (busy && !history ? "当前回复片段：\n" : "最近回复：\n") + answer));
    }

    private static String visibleText(ClaudeSession.Message message, int maxLength) {
        JsonObject raw = message.raw;
        if (raw != null) {
            if (raw.has("message") && raw.get("message").isJsonObject()) {
                raw = raw.getAsJsonObject("message");
            }
            JsonElement content = raw.get("content");
            if (content != null && content.isJsonArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonElement element : content.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject block = element.getAsJsonObject();
                    if (block.has("type") && block.get("type").isJsonPrimitive()
                            && "text".equals(block.get("type").getAsString())
                            && block.has("text") && block.get("text").isJsonPrimitive()
                            && block.get("text").getAsJsonPrimitive().isString()) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(bounded(block.get("text").getAsString(), maxLength));
                        if (text.length() >= maxLength) {
                            break;
                        }
                    }
                }
                return bounded(text.toString(), maxLength);
            }
        }
        return "[tool_result]".equals(message.content) ? "" : bounded(message.content, maxLength);
    }

    /** Decode transport text only when a structured question in the same thread proves its owner. */
    private static String codexAnswer(List<ClaudeSession.Message> messages, int index) {
        ClaudeSession.Message reply = messages.get(index);
        String text = visibleText(reply, Integer.MAX_VALUE);
        var prefix = CODEX_ANSWER.matcher(text);
        if (!prefix.find() || reply.raw == null || string(reply.raw, "codexThreadId") == null) {
            return null;
        }
        String request;
        try {
            request = prefix.group(1) == null ? null : URLDecoder.decode(prefix.group(1), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        String body = text.substring(prefix.end());
        for (int previous = index - 1; previous >= Math.max(0, index - 256); previous--) {
            ClaudeSession.Message question = messages.get(previous);
            JsonObject raw = question.raw;
            if (question.type != ClaudeSession.Message.Type.ASSISTANT || raw == null
                    || !Objects.equals(string(reply.raw, "codexThreadId"), string(raw, "codexThreadId"))) {
                continue;
            }
            JsonObject container = raw.has("message") && raw.get("message").isJsonObject() ? raw.getAsJsonObject("message") : raw;
            JsonElement blocks = container.get("content");
            if (blocks == null || !blocks.isJsonArray()) {
                continue;
            }
            for (JsonElement element : blocks.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                if (!"codex_user_input".equals(string(block, "name")) || request != null && !request.equals(string(block, "id"))) {
                    continue;
                }
                JsonObject input = block.has("input") && block.get("input").isJsonObject() ? block.getAsJsonObject("input") : null;
                JsonElement questions = input == null ? null : input.get("questions");
                if (questions != null && questions.isJsonArray() && !questions.getAsJsonArray().isEmpty()
                        && matchesQuestionBody(body, questions.getAsJsonArray())) {
                    return body;
                }
            }
        }
        return null;
    }

    private static boolean matchesQuestionBody(String body, com.google.gson.JsonArray questions) {
        int position = 0;
        for (int index = 0; index < questions.size(); index++) {
            JsonElement value = questions.get(index);
            String title = value.isJsonObject() ? string(value.getAsJsonObject(), "question") : null;
            if (title == null || !body.startsWith(title + "\n", position)) {
                return false;
            }
            position += title.length() + 1;
            String nextTitle = index + 1 < questions.size() && questions.get(index + 1).isJsonObject()
                    ? string(questions.get(index + 1).getAsJsonObject(), "question") : null;
            int end = index + 1 == questions.size() ? body.length() : nextTitle == null ? -1 : body.indexOf("\n\n" + nextTitle + "\n", position);
            if (end < position || body.substring(position, end).isBlank()) {
                return false;
            }
            position = end + 2;
        }
        return true;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static String bounded(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        int end = Math.min(text.length(), maxLength);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        String safe = text.substring(0, end).replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "").trim();
        return text.length() > end ? safe + "…" : safe;
    }
}
