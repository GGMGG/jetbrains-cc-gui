package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.github.claudecodegui.testing.SessionMetadataBridgeFixture;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class HistoryDeleteServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void codexDeletionRequiresAConfirmedDatabaseAndFileReceipt() throws Exception {
        HistoryDeleteService.confirmedCodexDeletion(JsonParser.parseString(
                "{\"state\":\"COMPLETED\",\"result\":{\"deletedSessionIds\":[\"parent\",\"child\"]}}"
        ).getAsJsonObject());
        for (String state : List.of("FAILED_CLEAN", "RECONCILIATION_REQUIRED", "COMMITTING")) {
            var error = assertThrows(java.io.IOException.class, () -> HistoryDeleteService.confirmedCodexDeletion(
                    JsonParser.parseString("{\"state\":\"" + state + "\",\"error\":\"DATABASE_BUSY\"}").getAsJsonObject()));
            assertEquals("DATABASE_BUSY", error.getMessage());
        }
    }

    @Test
    public void parseSessionIdsAcceptsArrayPayload() {
        assertEquals(
                Arrays.asList("session-one", "session-two"),
                HistoryDeleteService.parseSessionIds("[\"session-one\",\"session-two\"]"));
    }

    @Test
    public void parseSessionIdsAcceptsObjectPayload() {
        assertEquals(
                Arrays.asList("session-one", "session-two"),
                HistoryDeleteService.parseSessionIds("{\"sessionIds\":[\"session-one\",\"session-two\"]}"));
    }

    @Test
    public void parseSessionIdsTrimsAndDeduplicates() {
        assertEquals(
                Arrays.asList("session-one", "session-two"),
                HistoryDeleteService.parseSessionIds("[\" session-one \",\"session-two\",\"session-one\",\"\"]"));
    }

    @Test
    public void parseSessionIdsRejectsMissingPayload() {
        assertEquals(Collections.emptyList(), HistoryDeleteService.parseSessionIds(""));
        assertEquals(Collections.emptyList(), HistoryDeleteService.parseSessionIds(null));
    }

    @Test
    public void parseSessionIdsRejectsMalformedPayload() {
        assertEquals(Collections.emptyList(), HistoryDeleteService.parseSessionIds("["));
    }

    @Test
    public void codexFileMatchAnchorsToHyphenAndExtension() {
        String sessionId = "019b690b-c87f-7350-8f45-bc3dbb59ff77";
        Path matching = Paths.get("/tmp/rollout-2025-12-29T15-38-58-" + sessionId + ".jsonl");
        assertTrue(HistoryDeleteService.isCodexSessionFileMatch(matching, sessionId));
    }

    @Test
    public void codexFileMatchRejectsSubstringWithinNeighbouringSessionId() {
        // Different session whose UUID merely contains the target as a substring
        String target = "abcd1234";
        Path neighbour = Paths.get("/tmp/rollout-2025-12-29T15-38-58-prefix" + target + "suffix.jsonl");
        assertFalse(HistoryDeleteService.isCodexSessionFileMatch(neighbour, target));
    }

    @Test
    public void codexFileMatchRejectsNonJsonlExtension() {
        String sessionId = "019b690b-c87f-7350";
        Path wrongExt = Paths.get("/tmp/rollout-2025-12-29T15-38-58-" + sessionId + ".log");
        assertFalse(HistoryDeleteService.isCodexSessionFileMatch(wrongExt, sessionId));
    }

    @Test
    public void codexFileDiscoveryIncludesSubagentDescendantsWithoutParentRollout() throws Exception {
        String parentId = "019f6887-814a-7af1-ab43-4fa5e5fb8e1b";
        String childId = "019f6889-a450-7bb3-8589-59de68b6bb4d";
        String grandchildId = "019f688a-a450-7bb3-8589-59de68b6bb4d";
        Path sessionDir = temporaryFolder.newFolder("sessions").toPath();

        Path child = writeCodexRollout(sessionDir, childId, parentId, "child");
        Path grandchild = writeCodexRollout(sessionDir, grandchildId, childId, "grandchild");
        Path unrelated = writeCodexRollout(sessionDir, "019f6999-a450-7bb3-8589-59de68b6bb4d",
                "019f6000-0000-0000-0000-000000000000", parentId);

        List<Path> matches = HistoryDeleteService.findCodexSessionFiles(sessionDir, parentId);

        assertEquals(new HashSet<>(Arrays.asList(child, grandchild)), new HashSet<>(matches));
        assertFalse(matches.contains(unrelated));
    }

    @Test
    public void codexBatchDiscoveryBuildsOneSnapshotForMultipleRoots() throws Exception {
        String parentId = "019f6887-814a-7af1-ab43-4fa5e5fb8e1b";
        String childId = "019f6889-a450-7bb3-8589-59de68b6bb4d";
        String grandchildId = "019f688a-a450-7bb3-8589-59de68b6bb4d";
        Path sessionDir = temporaryFolder.newFolder("batch-sessions").toPath();

        Path child = writeCodexRollout(sessionDir, childId, parentId, "child");
        Path grandchild = writeCodexRollout(sessionDir, grandchildId, childId, "grandchild");

        Map<String, List<Path>> matches = HistoryDeleteService.findCodexSessionFiles(
                sessionDir, Arrays.asList(parentId, childId));

        assertEquals(new HashSet<>(Arrays.asList(child, grandchild)),
                new HashSet<>(matches.get(parentId)));
        assertEquals(new HashSet<>(Arrays.asList(child, grandchild)),
                new HashSet<>(matches.get(childId)));
    }

    @Test
    public void codexDeletionCompletesOnlyWhenEveryMatchedFileSucceeds() {
        Path parent = Paths.get("parent.jsonl");
        Path child = Paths.get("child.jsonl");

        assertTrue(HistoryDeleteService.isCodexSessionDeletionComplete(
                Arrays.asList(parent, child), Collections.emptySet()));
        assertFalse(HistoryDeleteService.isCodexSessionDeletionComplete(
                Arrays.asList(parent, child), Collections.singleton(child)));
        assertFalse(HistoryDeleteService.isCodexSessionDeletionComplete(
                Collections.emptyList(), Collections.emptySet()));
    }

    @Test
    public void quiescesOnlyTheMatchingProviderSessionBeforeDeletion() {
        RecordingClaudeSession matching = new RecordingClaudeSession("session-1", "codex");
        HistoryDeleteService.quiesceActiveSessionForDeletion(
                matching, Collections.singleton("session-1"), "codex").join();
        assertFalse(matching.interrupted);

        RecordingClaudeSession otherSession = new RecordingClaudeSession("session-2", "codex");
        HistoryDeleteService.quiesceActiveSessionForDeletion(
                otherSession, Collections.singleton("session-1"), "codex").join();
        assertFalse(otherSession.interrupted);

        RecordingClaudeSession otherProvider = new RecordingClaudeSession("session-1", "claude");
        HistoryDeleteService.quiesceActiveSessionForDeletion(
                otherProvider, Collections.singleton("session-1"), "codex").join();
        assertFalse(otherProvider.interrupted);
    }

    @Test
    public void failedQuiesceDoesNotStartDeletionContinuation() {
        RecordingClaudeSession matching = new RecordingClaudeSession("session-1", "claude") {
            @Override
            public CompletableFuture<Void> interrupt() {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("interrupt failed"));
                return failed;
            }
        };
        AtomicBoolean deletionStarted = new AtomicBoolean(false);

        CompletableFuture<Void> deletion = HistoryDeleteService.quiesceActiveSessionForDeletion(
                matching, Collections.singleton("session-1"), "claude")
                .thenRun(() -> deletionStarted.set(true));

        try {
            deletion.join();
        } catch (CompletionException expected) {
            assertEquals("interrupt failed", expected.getCause().getMessage());
        }
        assertFalse(deletionStarted.get());
        assertTrue(deletion.isCompletedExceptionally());
    }

    @Test
    public void legacyHistoryDeletionAlsoWaitsForThePersistentNativeWriterRelease() {
        CompletableFuture<JsonObject> released = new CompletableFuture<>();
        CodexSDKBridge bridge = new CodexSDKBridge() {
            @Override
            public CompletableFuture<JsonObject> releaseIdleCodexThread(String channelId, String cwd, String threadId) {
                assertEquals("session-1", threadId);
                return released;
            }
        };
        try {
            RecordingClaudeSession matching = new RecordingClaudeSession("session-1", "codex");
            var deletion = HistoryDeleteService.quiesceActiveSessionForDeletion(
                    matching, List.of("session-1"), "codex", bridge);
            assertFalse(matching.interrupted);
            assertFalse(deletion.isDone());
            released.complete(new JsonObject());
            deletion.join();
        } finally {
            bridge.cleanupAllProcesses();
        }
    }

    @Test
    public void activeCodexDeletionIsRejectedWithoutInterruptingItsTurnOrReleasingTheWriter() {
        RecordingClaudeSession session = new RecordingClaudeSession("session-1", "codex");
        Object owner = session.getState().beginTurn();
        AtomicBoolean released = new AtomicBoolean();
        CodexSDKBridge bridge = new CodexSDKBridge() {
            @Override
            public CompletableFuture<JsonObject> releaseIdleCodexThread(String channel, String cwd, String thread) {
                released.set(true);
                return CompletableFuture.completedFuture(new JsonObject());
            }
        };
        var failure = assertThrows(CompletionException.class, () -> HistoryDeleteService.quiesceActiveSessionForDeletion(
                session, List.of("session-1"), "codex", bridge).join());
        assertEquals("SESSION_RUNNING", failure.getCause().getMessage());
        assertFalse(session.interrupted);
        assertFalse(released.get());
        assertTrue(session.getState().isCurrentTurn(owner));
    }

    @Test
    public void nativeBusyRefusalDoesNotFallBackToInterruptOrStartDeletion() {
        RecordingClaudeSession session = new RecordingClaudeSession("session-1", "codex");
        CodexSDKBridge bridge = new CodexSDKBridge() {
            @Override
            public CompletableFuture<JsonObject> releaseIdleCodexThread(String channel, String cwd, String thread) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "SESSION_RUNNING");
                return CompletableFuture.completedFuture(error);
            }
        };
        AtomicBoolean deleted = new AtomicBoolean();
        var operation = HistoryDeleteService.quiesceActiveSessionForDeletion(session, List.of("session-1"), "codex", bridge)
                .thenRun(() -> deleted.set(true));
        assertThrows(CompletionException.class, operation::join);
        assertFalse(session.interrupted);
        assertFalse(deleted.get());
    }

    @Test
    public void ownedThreadsInOtherWindowsAndOwnedSubagentsAreProtectedBeforeLookingAtTheSelectedRoot() {
        for (boolean ownedByCurrentBridge : new boolean[]{false, true}) {
            RecordingClaudeSession session = new RecordingClaudeSession("current-root", "codex");
            CodexSDKBridge bridge = new CodexSDKBridge() {
                @Override
                public boolean hasCodexThreadOwner(String thread) {
                    return "other-owned-thread".equals(thread);
                }

                @Override
                public boolean ownsCodexThread(String thread) {
                    return ownedByCurrentBridge;
                }
            };
            var failure = assertThrows(CompletionException.class, () -> HistoryDeleteService.quiesceActiveSessionForDeletion(
                    session, List.of("other-owned-thread"), "codex", bridge).join());
            assertEquals("SESSION_RUNNING", failure.getCause().getMessage());
            assertFalse(session.interrupted);
        }
    }

    @Test
    public void abortedDeletionReloadsHistoryAfterOptimisticFrontendRemoval() throws Exception {
        HandlerContext context = new HandlerContext(null, null, null, null, null);
        RecordingClaudeSession session = new RecordingClaudeSession("session-1", "codex");
        session.getState().beginTurn();
        context.setSession(session);
        RecordingHistoryLoadService historyLoadService = new RecordingHistoryLoadService(context);
        HistoryDeleteService service = new HistoryDeleteService(context, null, historyLoadService);

        service.handleDeleteSession("session-1", "codex");

        assertTrue(historyLoadService.awaitReload());
        assertEquals("codex", historyLoadService.provider);
    }

    @Test
    public void cleanupSessionMetadataRemovesFavoriteAndCustomTitle() throws Exception {
        SessionMetadataBridgeFixture fixture =
                SessionMetadataBridgeFixture.create(temporaryFolder.newFolder("metadata").toPath());
        fixture.seedFavorites("session-1", "session-2");
        fixture.seedTitles("session-1", "session-2");
        HistoryDeleteService service = new HistoryDeleteService(
                fixture.context(), new NodeJsServiceCaller(fixture.context()), null);

        assertTrue(service.cleanupSessionMetadata("session-1"));

        assertEquals(Set.of("session-2"), fixture.favoriteIds());
        assertEquals(Set.of("session-2"), fixture.titledSessionIds());
    }

    @Test
    public void cleanupSessionMetadataDeletesTitleEvenWhenFavoriteRemovalFails() {
        RecordingNodeJsServiceCaller caller = new RecordingNodeJsServiceCaller(null, "{\"success\":true}");

        assertFalse(new HistoryDeleteService(null, caller, null).cleanupSessionMetadata("session-1"));

        assertEquals(Arrays.asList("removeFavorite:session-1", "deleteTitle:session-1"), caller.calls);
    }

    @Test
    public void cleanupSessionMetadataSwallowsTitleDeletionFailure() {
        RecordingNodeJsServiceCaller caller = new RecordingNodeJsServiceCaller("true", null);

        assertFalse(new HistoryDeleteService(null, caller, null).cleanupSessionMetadata("session-1"));

        assertEquals(Arrays.asList("removeFavorite:session-1", "deleteTitle:session-1"), caller.calls);
    }

    @Test
    public void cleanupSessionMetadataTreatsFailedServiceResultAsFailure() {
        // A failed save inside the JS services still exits 0 and prints false.
        RecordingNodeJsServiceCaller caller = new RecordingNodeJsServiceCaller("false", "{\"success\":false}");

        assertFalse(new HistoryDeleteService(null, caller, null).cleanupSessionMetadata("session-1"));

        assertEquals(Arrays.asList("removeFavorite:session-1", "deleteTitle:session-1"), caller.calls);
    }

    private Path writeCodexRollout(Path sessionDir, String sessionId, String parentId, String body)
            throws Exception {
        Path path = sessionDir.resolve("rollout-2026-07-16T09-27-59-" + sessionId + ".jsonl");
        String metadata = "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + sessionId
                + "\",\"source\":{\"subagent\":{\"thread_spawn\":{\"parent_thread_id\":\""
                + parentId + "\",\"depth\":1}}}}}";
        Files.write(path, Arrays.asList(metadata, body), StandardCharsets.UTF_8);
        return path;
    }

    private static class RecordingClaudeSession extends ClaudeSession {
        private final String sessionId;
        private final String provider;
        private boolean interrupted;

        private RecordingClaudeSession(String sessionId, String provider) {
            super(null, null, null, null);
            this.sessionId = sessionId;
            this.provider = provider;
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public String getProvider() {
            return provider;
        }

        @Override
        public CompletableFuture<Void> interrupt() {
            interrupted = true;
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Records calls; a null result makes that call throw. */
    private static class RecordingNodeJsServiceCaller extends NodeJsServiceCaller {
        private final String favoritesResult;
        private final String titlesResult;
        private final List<String> calls = new ArrayList<>();

        private RecordingNodeJsServiceCaller(String favoritesResult, String titlesResult) {
            super(null);
            this.favoritesResult = favoritesResult;
            this.titlesResult = titlesResult;
        }

        @Override
        public String callNodeJsFavoritesService(String functionName, String sessionId) throws Exception {
            calls.add(functionName + ":" + sessionId);
            if (favoritesResult == null) {
                throw new IllegalArgumentException("favorites failed");
            }
            return favoritesResult;
        }

        @Override
        public String callNodeJsDeleteTitle(String sessionId) throws Exception {
            calls.add("deleteTitle:" + sessionId);
            if (titlesResult == null) {
                throw new Exception("titles failed");
            }
            return titlesResult;
        }
    }

    private static class RecordingHistoryLoadService extends HistoryLoadService {
        private final CountDownLatch reloaded = new CountDownLatch(1);
        private String provider;

        private RecordingHistoryLoadService(HandlerContext context) {
            super(context, null);
        }

        @Override
        void handleLoadHistoryData(String provider) {
            this.provider = provider;
            reloaded.countDown();
        }

        private boolean awaitReload() throws InterruptedException {
            return reloaded.await(5, TimeUnit.SECONDS);
        }
    }
}
