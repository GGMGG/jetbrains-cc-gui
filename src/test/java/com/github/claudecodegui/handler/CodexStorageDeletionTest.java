package com.github.claudecodegui.handler;

import com.github.claudecodegui.session.ClaudeSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CodexStorageDeletionTest {
    @Test
    public void storageHandlerDoesNotClaimEitherLegacyHistoryAction() {
        CodexStorageHandler handler = new CodexStorageHandler(null);
        assertFalse(handler.handle("delete_session", "legacy-session"));
        assertFalse(handler.handle("delete_sessions", "[\"legacy-session\"]"));
        assertTrue(Arrays.asList(CodexStorageHandler.TYPES).contains("delete_ai_storage_sessions"));
    }

    @Test
    public void storageDeletionRejectsTheRunningTargetWithoutInterruptingIt() {
        RecordingSession session = new RecordingSession("target", "codex", true);
        IOException error = assertThrows(IOException.class, () -> CodexStorageHandler.requireIdleSession(session, List.of("target")));
        assertEquals("SESSION_RUNNING", error.getMessage());
        assertFalse(session.interrupted);
    }

    @Test
    public void idleAndUnrelatedSessionsDoNotBlockStorageDeletion() throws Exception {
        CodexStorageHandler.requireIdleSession(null, List.of("target"));
        CodexStorageHandler.requireIdleSession(new RecordingSession("target", "codex", false), List.of("target"));
        CodexStorageHandler.requireIdleSession(new RecordingSession("other", "codex", true), List.of("target"));
        CodexStorageHandler.requireIdleSession(new RecordingSession("target", "claude", true), List.of("target"));
    }

    @Test
    public void storageTargetsRequireABoundedArrayOfSafeSessionIds() throws Exception {
        JsonObject request = JsonParser.parseString("{\"sessionIds\":[\"session-1\",\"session-2\"]}").getAsJsonObject();
        assertEquals(List.of("session-1", "session-2"), CodexStorageHandler.deletionTargets(request));
        for (String invalid : List.of("{}", "{\"sessionIds\":null}", "{\"sessionIds\":\"one\"}",
                "{\"sessionIds\":[]}", "{\"sessionIds\":[1]}", "{\"sessionIds\":[{}]}", "{\"sessionIds\":[\"../outside\"]}")) {
            assertThrows(IOException.class, () -> CodexStorageHandler.deletionTargets(JsonParser.parseString(invalid).getAsJsonObject()));
        }
        JsonArray tooMany = new JsonArray();
        for (int index = 0; index <= 1000; index++) {
            tooMany.add("session-" + index);
        }
        request.add("sessionIds", tooMany);
        assertThrows(IOException.class, () -> CodexStorageHandler.deletionTargets(request));
    }

    @Test
    public void metadataFailureStillAttemptsBothIndependentStorageCleanups() {
        AtomicBoolean titleAttempted = new AtomicBoolean();
        NodeJsServiceCaller caller = new NodeJsServiceCaller(null) {
            @Override
            public String callNodeJsFavoritesService(String functionName, String sessionId) throws Exception {
                throw new IOException("fixture");
            }

            @Override
            public String callNodeJsDeleteTitle(String sessionId) {
                titleAttempted.set(true);
                return "{\"success\":true}";
            }
        };
        assertFalse(CodexStorageHandler.cleanupSessionMetadata(caller, "session-1"));
        assertTrue(titleAttempted.get());
    }

    @Test
    public void storageMetadataReceiptRequiresSuccessfulServiceResponses() {
        for (String titleResult : List.of("{\"success\":true}", "{\"success\":false}", "{}", "invalid")) {
            NodeJsServiceCaller caller = new NodeJsServiceCaller(null) {
                @Override
                public String callNodeJsFavoritesService(String functionName, String sessionId) {
                    return "true";
                }

                @Override
                public String callNodeJsDeleteTitle(String sessionId) {
                    return titleResult;
                }
            };
            assertEquals(titleResult.equals("{\"success\":true}"), CodexStorageHandler.cleanupSessionMetadata(caller, "session-1"));
        }
    }

    private static class RecordingSession extends ClaudeSession {
        private final String id;
        private final String provider;
        private final boolean busy;
        private boolean interrupted;

        private RecordingSession(String id, String provider, boolean busy) {
            super(null, null, null, null);
            this.id = id;
            this.provider = provider;
            this.busy = busy;
        }

        @Override public String getSessionId() { return id; }
        @Override public String getProvider() { return provider; }
        @Override public boolean isBusy() { return busy; }
        @Override public CompletableFuture<Void> interrupt() {
            interrupted = true;
            return CompletableFuture.completedFuture(null);
        }
    }
}
