package com.github.claudecodegui.pet.office;

import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.SessionExecutionOwnership;
import com.github.claudecodegui.session.SessionExecutionState;
import com.github.claudecodegui.session.SessionState;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class PetOfficeSessionRegistryTest {
    private final AtomicLong millis = new AtomicLong(1_000);
    private PetOfficeSessionRegistry registry;

    @Before
    public void setUp() {
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        };
        registry = new PetOfficeSessionRegistry("instance-A", clock, () -> millis.get() * 1_000_000);
    }

    @Test
    public void historyLoadingAndCachedBusyDoNotStartATurn() {
        SessionState state = new SessionState();
        registry.register(metadata("tab-A", "project-A"), state);
        state.setBusy(true);
        state.setLoading(true);
        state.setRestoredSessionId("PRIVATE_HISTORY_ID");
        registry.observe();
        assertEquals(1, registry.snapshot().sources().size());
        assertEquals(SessionExecutionState.Phase.IDLE, onlySource().execution().phase());
        assertTrue(onlySource().execution().turnId().isEmpty());
    }

    @Test
    public void backgroundTabsAndProjectsRemainSeparateEvenForTheSameTranscript() {
        SessionState first = new SessionState();
        SessionState second = new SessionState();
        first.setRestoredSessionId("same-transcript");
        second.setRestoredSessionId("same-transcript");
        registry.register(metadata("tab-A", "project-A"), first);
        registry.register(metadata("tab-B", "project-B"), second);
        first.beginTurn();
        second.beginTurn();
        CompletableFuture<SDKResult> firstBackend = new CompletableFuture<>();
        CompletableFuture<SDKResult> secondBackend = new CompletableFuture<>();
        first.currentExecutionOwnership().bindResult(firstBackend);
        second.currentExecutionOwnership().bindResult(secondBackend);
        advance(20_000);
        registry.observe();
        assertEquals(2, registry.snapshot().sources().size());
        assertTrue(registry.snapshot().sources().stream().allMatch(source -> source.execution().phase() == SessionExecutionState.Phase.RUNNING));
        assertNotEquals(registry.snapshot().sources().get(0).sourceKey(), registry.snapshot().sources().get(1).sourceKey());
        assertEquals(0, registry.snapshot().sources().get(0).observationAgeMillis());
    }

    @Test
    public void renameModelAndInitialProviderIdentityKeepTheActorBinding() {
        SessionState state = new SessionState();
        PetOfficeSessionRegistry.Registration registration = registry.register(metadata("tab-A", "project-A"), state);
        state.beginTurn();
        PetOfficeSessionRegistry.SourceKey original = onlySource().sourceKey();
        registration.rename("Renamed tab");
        state.setModelVerbatim("custom-model");
        state.setSessionId("PRIVATE_NEW_PROVIDER_ID");
        assertEquals(original, onlySource().sourceKey());
        assertEquals("Renamed tab", onlySource().metadata().tabLabel());
        assertEquals("custom-model", onlySource().modelLabel());
        assertEquals(SessionExecutionState.Phase.RUNNING, onlySource().execution().phase());
    }

    @Test
    public void replacingBindingKeepsOldLiveTaskDetachedAndLateResultCannotFinishTheReplacement() {
        SessionState state = new SessionState();
        registry.register(metadata("tab-A", "project-A"), state);
        state.beginTurn();
        CompletableFuture<SDKResult> backend = new CompletableFuture<>();
        SessionExecutionOwnership old = state.currentExecutionOwnership();
        old.bindResult(backend);
        String oldRoute = onlySource().routeGeneration();
        state.setProvider("codex");
        state.beginTurn();
        assertEquals(2, registry.snapshot().sources().size());
        PetOfficeSessionRegistry.SourceSnapshot detached = registry.snapshot().sources().get(0);
        assertEquals(PetOfficeSessionRegistry.RouteStatus.DETACHED, detached.routeStatus());
        assertNotEquals(oldRoute, detached.routeGeneration());
        SessionExecutionState.Snapshot replacement = state.getExecutionSnapshot();
        backend.complete(SDKResult.success("PRIVATE_OLD_RESULT"));
        registry.observe();
        assertEquals(replacement, state.getExecutionSnapshot());
        assertEquals(SessionExecutionState.Outcome.SUCCEEDED, registry.snapshot().sources().get(0).execution().outcome());
        advance(5_000);
        registry.observe();
        assertEquals(1, registry.snapshot().sources().size());
        assertEquals(1, registry.snapshot().recentResults().size());
    }

    @Test
    public void sessionObjectReplacementAndObsoleteStateUpdatesCannotRebindTheNewOwner() {
        SessionState first = new SessionState();
        PetOfficeSessionRegistry.Registration registration = registry.register(metadata("tab-A", "project-A"), first);
        SessionState second = new SessionState();
        registration.replaceSession(second);
        PetOfficeSessionRegistry.SourceKey key = onlySource().sourceKey();
        first.setProvider("codex");
        first.beginTurn();
        assertEquals(key, onlySource().sourceKey());
        registration.close();
        second.beginTurn();
        assertTrue(registry.snapshot().sources().isEmpty());
    }

    @Test
    public void closingRoutesImmediatelyKeepsActualBackendUntilItsTerminalResult() {
        SessionState state = new SessionState();
        PetOfficeSessionRegistry.Registration registration = registry.register(metadata("tab-A", "project-A"), state);
        state.beginTurn();
        CompletableFuture<SDKResult> backend = new CompletableFuture<>();
        state.currentExecutionOwnership().bindResult(backend);
        registration.close();
        assertEquals(PetOfficeSessionRegistry.RouteStatus.CLOSED, onlySource().routeStatus());
        assertFalse(backend.isDone());
        advance(35_000);
        registry.observe();
        assertEquals(1, registry.snapshot().sources().size());
        backend.complete(SDKResult.error("PRIVATE_ERROR"));
        registry.observe();
        assertEquals(SessionExecutionState.Outcome.FAILED, onlySource().execution().outcome());
        advance(5_000);
        registry.observe();
        assertTrue(registry.snapshot().sources().isEmpty());
        assertEquals(1, registry.snapshot().recentResults().size());
    }

    @Test
    public void cachedRunningPollsAndSnapshotReadsCannotRenewObservation() {
        SessionState state = new SessionState();
        registry.register(metadata("tab-A", "project-A"), state);
        state.beginTurn();
        long observed = onlySource().observationRevision();
        long watermark = registry.snapshot().watermark();
        advance(10_000);
        registry.observe();
        assertEquals(observed, onlySource().observationRevision());
        assertEquals(watermark, registry.snapshot().watermark());
        advance(5_000);
        assertEquals(SessionExecutionState.Phase.UNKNOWN, onlySource().execution().phase());
        assertEquals(15_000, onlySource().observationAgeMillis());
        assertEquals(watermark, registry.snapshot().watermark());
    }

    @Test
    public void closedUnprovenTaskRetiresWithoutInventingAnOutcome() {
        SessionState state = new SessionState();
        PetOfficeSessionRegistry.Registration registration = registry.register(metadata("tab-A", "project-A"), state);
        state.beginTurn();
        registration.close();
        advance(15_000);
        registry.observe();
        assertEquals(SessionExecutionState.Phase.UNKNOWN, onlySource().execution().phase());
        assertEquals(SessionExecutionState.Outcome.NONE, onlySource().execution().outcome());
        advance(15_000);
        registry.observe();
        assertTrue(registry.snapshot().sources().isEmpty());
        assertTrue(registry.snapshot().recentResults().isEmpty());
    }

    @Test
    public void realQuestionFutureRenewsProofAndExceptionalAnswerDoesNotFailTheTask() {
        SessionState state = new SessionState();
        registry.register(metadata("tab-A", "project-A"), state);
        state.beginTurn();
        CompletableFuture<String> answer = new CompletableFuture<>();
        state.currentExecutionOwnership().bindInteraction(SessionExecutionState.WaitReason.QUESTION, answer);
        advance(20_000);
        registry.observe();
        assertEquals(SessionExecutionState.Phase.WAITING_USER, onlySource().execution().phase());
        assertEquals(0, onlySource().observationAgeMillis());
        answer.completeExceptionally(new IllegalStateException("Expired"));
        registry.observe();
        assertEquals(SessionExecutionState.WaitReason.NONE, onlySource().execution().waitReason());
        assertEquals(SessionExecutionState.Outcome.NONE, onlySource().execution().outcome());
    }

    @Test
    public void terminalGraceAndBoundedRecentResultsUseMonotonicAge() {
        SessionState state = new SessionState();
        registry.register(metadata("tab-A", "project-A"), state);
        for (int index = 0; index < 60; index++) {
            state.beginTurn();
            SessionExecutionOwnership owner = state.currentExecutionOwnership();
            owner.state().finish(owner.turn(), SessionExecutionState.Outcome.SUCCEEDED);
        }
        registry.observe();
        assertEquals(50, registry.snapshot().recentResults().size());
        assertEquals(1, registry.snapshot().sources().size());
        advance(5_000);
        registry.observe();
        assertEquals(SessionExecutionState.Phase.IDLE, onlySource().execution().phase());
        registry.observe();
        assertEquals(50, registry.snapshot().recentResults().size());
        advance(595_000);
        registry.observe();
        assertTrue(registry.snapshot().recentResults().isEmpty());
    }

    @Test
    public void capacityRejectsExtraSourcesWithoutErasingTheLastCompleteSet() {
        for (int index = 0; index < 256; index++) {
            assertNotNull(registry.register(metadata("tab-" + index, "project-A"), new SessionState()));
        }
        assertNull(registry.register(metadata("overflow", "project-A"), new SessionState()));
        assertEquals(256, registry.snapshot().sources().size());
        assertTrue(registry.snapshot().capacityReached());
        registry.close();
        assertTrue(registry.snapshot().sources().isEmpty());
    }

    @Test
    public void completeSnapshotIsImmutableBoundedAndContainsNoConversationOrProviderIds() {
        SessionState state = new SessionState();
        state.setCwd("PRIVATE_PATH");
        state.setSummary("PRIVATE_SUMMARY");
        state.setError("PRIVATE_ERROR");
        state.setSessionId("PRIVATE_ID");
        registry.register(new PetOfficeSessionRegistry.Metadata("tab-A", "IC", "IDEA", "project-A",
                "Project\u0000\u202e name", "x".repeat(300)), state);
        String exported = new Gson().toJson(registry.snapshot());
        assertFalse(exported.contains("PRIVATE_"));
        assertEquals(160, onlySource().metadata().tabLabel().length());
        assertEquals("Project name", onlySource().metadata().projectLabel());
        try {
            registry.snapshot().sources().clear();
            fail("Expected an immutable complete source set");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, registry.snapshot().sources().size());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rawPathsAreRejectedAsOpaqueIdentifiers() {
        metadata("C:/PRIVATE_WORKSPACE", "project-A");
    }

    @Test
    public void unsupportedProviderRemainsUnknownDuringARealTurn() {
        SessionState state = new SessionState();
        state.setProvider("grok");
        registry.register(metadata("tab-A", "project-A"), state);
        state.beginTurn();
        assertEquals(SessionExecutionState.Phase.UNKNOWN, onlySource().execution().phase());
        assertEquals(SessionExecutionState.Outcome.NONE, onlySource().execution().outcome());
        SessionExecutionOwnership owner = state.currentExecutionOwnership();
        owner.state().finish(owner.turn(), SessionExecutionState.Outcome.SUCCEEDED);
        registry.observe();
        assertEquals(SessionExecutionState.Phase.UNKNOWN, onlySource().execution().phase());
        assertEquals(SessionExecutionState.Outcome.NONE, onlySource().execution().outcome());
        assertTrue(registry.snapshot().recentResults().isEmpty());
    }

    private PetOfficeSessionRegistry.SourceSnapshot onlySource() {
        assertEquals(1, registry.snapshot().sources().size());
        return registry.snapshot().sources().get(0);
    }

    private static PetOfficeSessionRegistry.Metadata metadata(String tab, String project) {
        return new PetOfficeSessionRegistry.Metadata(tab, "IC", "IDEA", project, project, "Chat");
    }

    private void advance(long amount) {
        millis.addAndGet(amount);
    }
}
