package com.github.claudecodegui.clawbot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class ClawBotGatewayRuntimeServiceTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void onlyPendingSentAndUnknownAreStableDeliveryStates() {
        assertTrue(ClawBotGatewayRuntimeService.isStableDeliveryState("PENDING"));
        assertTrue(ClawBotGatewayRuntimeService.isStableDeliveryState("SENT"));
        assertTrue(ClawBotGatewayRuntimeService.isStableDeliveryState("UNKNOWN"));
        assertFalse(ClawBotGatewayRuntimeService.isStableDeliveryState("FAILED"));
        assertTrue(ClawBotGatewayRuntimeService.isTerminalDeliveryState("FAILED"));
    }

    @Test
    public void countsBridgeFilteredMessagesBeforeRouting() throws Exception {
        ClawBotGatewayRuntimeService service = new ClawBotGatewayRuntimeService(
                temporaryFolder.newFolder("inbound-counts").toPath(), "gateway-instance", testHandoff());
        try {
            JsonObject batch = new JsonObject();
            batch.addProperty("receivedCount", 3);
            batch.addProperty("droppedCount", 2);
            service.recordInboundBatch(batch, 1);
            service.recordInboundBatch(new JsonObject(), 1);

            assertEquals(4, service.statusSnapshot().get("inboundMessageCount").getAsInt());
            assertEquals(2, service.statusSnapshot().get("inboundDroppedCount").getAsInt());

            batch.addProperty("droppedCount", 3);
            assertThrows(java.io.IOException.class, () -> service.recordInboundBatch(batch, 1));
            batch.addProperty("receivedCount", 1.5);
            assertThrows(java.io.IOException.class, () -> service.recordInboundBatch(batch, 1));
            assertEquals(4, service.statusSnapshot().get("inboundMessageCount").getAsInt());
            assertEquals(2, service.statusSnapshot().get("inboundDroppedCount").getAsInt());
        } finally {
            service.dispose();
        }
    }

    @Test
    public void malformedInboundShapesAreNormalizedForPerMessageDropHandling() {
        JsonObject invalidTarget = inboundJson("invalid-target");
        invalidTarget.addProperty("target", "not-an-object");
        JsonObject invalidRevision = inboundJson("invalid-revision");
        invalidRevision.addProperty("routeRevision", "not-a-number");

        assertThrows(IllegalArgumentException.class, () -> ClawBotInboundMessage.fromJson(invalidTarget));
        assertThrows(IllegalArgumentException.class, () -> ClawBotInboundMessage.fromJson(invalidRevision));
    }

    @Test
    public void saturatedReplyQueueRunsRejectedTaskOnSubmittingThread() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), ClawBotGatewayRuntimeService::runRejectedReplyOnCaller);
        try {
            executor.execute(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            executor.execute(() -> { });

            Thread submittingThread = Thread.currentThread();
            AtomicReference<Thread> executionThread = new AtomicReference<>();
            executor.execute(() -> executionThread.set(Thread.currentThread()));

            assertSame(submittingThread, executionThread.get());
        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void leaderServesSessionRegistrationAndMockChannelUpdates() throws Exception {
        ClawBotGatewayRuntimeService service = new ClawBotGatewayRuntimeService(
                temporaryFolder.newFolder("leader-runtime").toPath(), "gateway-instance", testHandoff());
        try {
            assertTrue(service.start());
            try (ClawBotIdeClient client = new ClawBotIdeClient(service.endpoint(), "ide-instance", 4)) {
                ClawBotSessionRegistration registration = registration(ClawBotSessionStatus.ONLINE);

                assertTrue(client.register(registration));
                assertEquals(1, client.listSessions().size());
                assertEquals(1, service.mockChannel().sessions().size());
                assertTrue(client.heartbeat("session-1", ClawBotSessionStatus.BUSY));
                assertEquals(ClawBotSessionStatus.BUSY, client.listSessions().get(0).status());
                assertTrue(client.replaceSnapshot(List.of(registration(ClawBotSessionStatus.ONLINE))));
                assertEquals(ClawBotSessionStatus.ONLINE, client.listSessions().get(0).status());
                assertTrue(client.unregister("session-1"));
                assertTrue(client.listSessions().isEmpty());
                assertTrue(service.mockChannel().revision() >= 4);
            }
        } finally {
            service.stop();
        }
    }

    @Test
    public void periodicHeartbeatKeepsLatestBusyStatus() throws Exception {
        ClawBotGatewayRuntimeService service = new ClawBotGatewayRuntimeService(
                temporaryFolder.newFolder("heartbeat-runtime").toPath(), "gateway-instance", testHandoff());
        try {
            assertTrue(service.start());
            try (ClawBotIdeClient client = new ClawBotIdeClient(service::endpoint, "ide-instance", 4, 25)) {
                assertTrue(client.register(registration(ClawBotSessionStatus.ONLINE)));
                assertTrue(client.heartbeat("session-1", ClawBotSessionStatus.BUSY));
                Thread.sleep(200);
                assertEquals(ClawBotSessionStatus.BUSY, client.listSessions().get(0).status());
            }
        } finally {
            service.stop();
        }
    }

    @Test
    public void failedSessionUpdateIsReplayedByNextHeartbeat() throws Exception {
        ClawBotGatewayRuntimeService service = new ClawBotGatewayRuntimeService(
                temporaryFolder.newFolder("update-recovery-runtime").toPath(), "gateway-instance", testHandoff());
        AtomicBoolean failNextRequest = new AtomicBoolean();
        try {
            assertTrue(service.start());
            try (ClawBotIdeClient client = new ClawBotIdeClient(() -> {
                if (failNextRequest.getAndSet(false)) {
                    throw new java.io.IOException("simulated connection loss");
                }
                return service.endpoint();
            }, "ide-instance", 4, 25)) {
                assertTrue(client.register(registration(ClawBotSessionStatus.ONLINE)));
                failNextRequest.set(true);

                assertThrows(java.io.IOException.class,
                        () -> client.updateSession("session-1", "claude", "Renamed", "generation-2", ""));
                assertTrue(client.heartbeat("session-1", ClawBotSessionStatus.ONLINE));

                ClawBotSessionSnapshot recovered = client.listSessions().get(0);
                assertEquals("claude", recovered.provider());
                assertEquals("Renamed", recovered.tabDisplayName());
                assertEquals("generation-2", recovered.generation());
            }
        } finally {
            service.stop();
        }
    }

    @Test
    public void presentationUpdatesPreserveBusyStateAndOwnership() throws Exception {
        ClawBotGatewayRuntimeService service = new ClawBotGatewayRuntimeService(
                temporaryFolder.newFolder("presentation-runtime").toPath(), "gateway-instance", testHandoff());
        try {
            assertTrue(service.start());
            try (ClawBotIdeClient client = new ClawBotIdeClient(service.endpoint(), "ide-instance", 4)) {
                assertTrue(client.register(registration(ClawBotSessionStatus.BUSY)));
                assertTrue(client.updatePresentation("session-1", "codex", "Investigate timeout"));
                ClawBotSessionSnapshot snapshot = client.listSessions().get(0);
                assertEquals("Investigate timeout", snapshot.tabDisplayName());
                assertEquals("codex", snapshot.provider());
                assertEquals(ClawBotSessionStatus.BUSY, snapshot.status());
                assertEquals(4, snapshot.connectionEpoch());
                assertTrue(client.updatePresentation("session-1", "codex", "Investigate timeout"));
                assertFalse(client.updatePresentation("missing", "codex", "Other tab"));
            }
        } finally {
            service.stop();
        }
    }

    @Test
    public void onlyOneRuntimeBecomesLeader() throws Exception {
        java.nio.file.Path runtime = temporaryFolder.newFolder("shared-runtime").toPath();
        ClawBotGatewayRuntimeService first = new ClawBotGatewayRuntimeService(runtime, "first", testHandoff());
        ClawBotGatewayRuntimeService second = new ClawBotGatewayRuntimeService(runtime, "second", testHandoff());
        try {
            assertTrue(first.start());
            assertFalse(second.start());
            assertTrue(first.isLeader());
            assertFalse(second.isLeader());
        } finally {
            second.stop();
            first.stop();
        }
    }

    @Test
    public void sessionGenerationAndActivityRoundTripOverIpc() throws Exception {
        ClawBotGatewayRuntimeService service = new ClawBotGatewayRuntimeService(
                temporaryFolder.newFolder("strict-runtime").toPath(), "gateway-instance", testHandoff());
        try {
            assertTrue(service.start());
            try (ClawBotIdeClient client = new ClawBotIdeClient(service.endpoint(), "ide-instance", 4)) {
                ClawBotSessionRegistration registration = new ClawBotSessionRegistration("session-1", "ide-instance",
                        "project", "Project", "codex", Set.of("INBOUND", "CONTROL", "ROUTING_V2"),
                        ClawBotSessionStatus.ONLINE, 4, "Chat", "generation-1");
                assertTrue(client.register(registration));
                assertTrue(client.updateSession("session-1", "codex", "Chat", "generation-1", "task-1"));
                ClawBotSessionSnapshot busy = client.listSessions().get(0);
                assertEquals("generation-1", busy.generation());
                assertEquals(ClawBotSessionStatus.BUSY, busy.status());
                ClawBotInboundMessage message = new ClawBotInboundMessage("message", "sender", "context", "text").forTarget(busy);
                assertTrue(client.matchesTarget("session-1", message, "generation-1", "codex"));
                assertFalse(client.matchesTarget("session-1", message, "generation-2", "codex"));
                assertFalse(client.validateInbound("session-1", "not-queued"));
                assertEquals(null, client.pollPreview("session-1"));
                assertFalse(client.replyToPreview("session-1", "not-requested", "must not send"));
                assertTrue(client.updateSession("session-1", "claude", "Renamed", "generation-2", ""));
                ClawBotSessionSnapshot next = client.listSessions().get(0);
                assertEquals("generation-2", next.generation());
                assertEquals("Renamed", next.tabDisplayName());
                assertEquals(ClawBotSessionStatus.ONLINE, next.status());
            }
        } finally {
            service.stop();
        }
    }

    @Test
    public void rejectedInboundAndControlMessagesBecomeReplyEligibleOverIpc() throws Exception {
        Path runtime = temporaryFolder.newFolder("reject-inbound-runtime").toPath();
        ClawBotGatewayRuntimeService service = new ClawBotGatewayRuntimeService(
                runtime, "gateway-instance", testHandoff());
        try {
            assertTrue(service.start());
            try (ClawBotIdeClient client = new ClawBotIdeClient(service.endpoint(), "ide-instance", 4)) {
                ClawBotSessionRegistration registration = new ClawBotSessionRegistration(
                        "session-1", "ide-instance", "project", "Project", "codex",
                        Set.of("STATUS", "INBOUND", "CONTROL"), ClawBotSessionStatus.ONLINE,
                        4, "Chat", "generation-1");
                assertTrue(client.register(registration));
                ClawBotSessionSnapshot session = client.listSessions().get(0);
                ClawBotSessionRegistry registry = sessionRegistry(service);
                ClawBotInboundMessage inbound = new ClawBotInboundMessage(
                        "inbound-rejected", "sender", "context", "text").forTarget(session);
                ClawBotInboundMessage control = ClawBotInboundMessage.command(
                        new ClawBotInboundMessage("control-rejected", "sender", "context", "stop"),
                        ClawBotInboundAction.INTERRUPT).forTarget(session);
                assertTrue(registry.enqueueInbound("session-1", inbound));
                assertTrue(registry.enqueueCommand("session-1", control));
                assertEquals(inbound, client.pollInbound("session-1"));
                assertEquals(control, client.pollCommand("session-1"));

                assertThrows(java.io.IOException.class,
                        () -> client.rejectInbound("session-1", inbound.messageId(), "target changed"));
                assertThrows(java.io.IOException.class,
                        () -> client.rejectCommand("session-1", control.messageId(), "target changed"));

                assertTrue(registry.isInboundDispatched(
                        "session-1", "ide-instance", 4, inbound.messageId()));
                assertTrue(registry.isCommandDispatched(
                        "session-1", "ide-instance", 4, control.messageId()));
                assertEquals(inbound, registry.pollInbound("session-1", "ide-instance", 4));
                assertEquals(control, registry.pollCommand("session-1", "ide-instance", 4));
            }
        } finally {
            service.stop();
        }
    }

    @Test
    public void followerDiscoversLeaderAndTakesOverAfterLeaderStops() throws Exception {
        Path runtime = temporaryFolder.newFolder("handoff-runtime").toPath();
        ClawBotGatewayRuntimeService first = new ClawBotGatewayRuntimeService(runtime, "first", testHandoff());
        ClawBotGatewayRuntimeService second = new ClawBotGatewayRuntimeService(runtime, "second", testHandoff());
        try {
            assertTrue(first.start());
            assertFalse(second.start());

            try (ClawBotIdeClient client = second.createIdeClient("ide-instance", 4)) {
                AtomicBoolean recovered = new AtomicBoolean();
                client.setSessionRecoveryListener(() -> recovered.set(true));
                assertTrue(client.register(registration(ClawBotSessionStatus.ONLINE)));
                String leaderJson = Files.readString(runtime.resolve("leader.json"));
                assertFalse(leaderJson.contains("gateway.auth"));
                assertFalse(leaderJson.contains("authToken"));
                assertTrue(Files.isRegularFile(runtime.resolve("gateway.auth")));

                first.stop();
                assertTrue(second.attemptTakeover());
                assertTrue(second.isLeader());
                assertTrue(second.endpoint().port() > 0);
                assertThrows(java.io.IOException.class, () -> client.pollInbound("session-1"));
                assertEquals(null, client.pollInbound("session-1"));
                assertTrue(recovered.get());
                assertTrue(client.heartbeat("session-1", ClawBotSessionStatus.ONLINE));
                assertEquals(1, client.listSessions().size());
            }
        } finally {
            second.stop();
            first.stop();
        }
    }

    @Test
    public void statusSnapshotExposesOnlySanitizedBindingState() throws Exception {
        ClawBotBindingHandoff handoff = testHandoff();
        ClawBotGatewayRuntimeService service = new ClawBotGatewayRuntimeService(
                temporaryFolder.newFolder("binding-status-runtime").toPath(), "gateway-instance", handoff);
        try {
            JsonObject unbound = service.statusSnapshot();
            assertEquals("UNBOUND", unbound.get("bindingState").getAsString());
            assertEquals(0L, unbound.get("bindingRevision").getAsLong());
            assertEquals("NONE", unbound.get("bindingDiagnostic").getAsString());

            handoff.accept("fixture-bot", "https://ilinkai.weixin.qq.com", null, "fixture-token");
            JsonObject bound = service.statusSnapshot();
            assertEquals("BOUND", bound.get("bindingState").getAsString());
            assertEquals(1L, bound.get("bindingRevision").getAsLong());
            assertEquals("NONE", bound.get("bindingDiagnostic").getAsString());
            assertFalse(bound.toString().contains("fixture-token"));
            assertFalse(bound.has("botToken"));
        } finally {
            service.stop();
        }
    }

    @Test
    public void followerReadsSanitizedStatusFromLeader() throws Exception {
        Path runtime = temporaryFolder.newFolder("status-proxy-runtime").toPath();
        ClawBotBindingHandoff leaderHandoff = testHandoff();
        leaderHandoff.accept("fixture-bot", "https://ilinkai.weixin.qq.com", null, "fixture-token");
        ClawBotGatewayRuntimeService leader = new ClawBotGatewayRuntimeService(
                runtime, "leader", leaderHandoff);
        ClawBotGatewayRuntimeService follower = new ClawBotGatewayRuntimeService(
                runtime, "follower", testHandoff());
        try {
            assertTrue(leader.start());
            assertFalse(follower.start());

            JsonObject status = follower.statusSnapshot();
            assertEquals("LEADER", status.get("state").getAsString());
            assertEquals("BOUND", status.get("bindingState").getAsString());
            assertFalse(status.toString().contains("fixture-token"));
        } finally {
            follower.stop();
            leader.stop();
        }
    }

    @Test
    public void followerManagesSenderAuthorizationThroughLeaderIpc() throws Exception {
        Path runtime = temporaryFolder.newFolder("sender-access-runtime").toPath();
        ClawBotGatewayRuntimeService leader = new ClawBotGatewayRuntimeService(
                runtime, "leader", testHandoff());
        ClawBotGatewayRuntimeService follower = new ClawBotGatewayRuntimeService(
                runtime, "follower", testHandoff());
        try {
            assertTrue(leader.start());
            assertFalse(follower.start());

            for (int index = 0; index < 9; index++) {
                JsonObject payload = new JsonObject();
                payload.addProperty("senderId", "fixture-sender-" + index);
                follower.control("ALLOW_SENDER", payload);
            }

            JsonObject status = follower.statusSnapshot();
            assertEquals(9, status.get("senderAccessCount").getAsInt());
            assertFalse(status.toString().contains("fixture-sender-"));

            JsonObject listed = follower.control("LIST_SENDERS", new JsonObject());
            assertEquals(8, listed.getAsJsonArray("authorizedSenders").size());
            assertTrue(listed.get("senderHasMore").getAsBoolean());
            assertTrue(listed.get("senderLastUsedAt").isJsonObject());
            JsonObject nextPagePayload = new JsonObject();
            nextPagePayload.addProperty("offset", 8);
            JsonObject lastPage = follower.control("LIST_SENDERS", nextPagePayload);
            assertEquals("[\"fixture-sender-8\"]", lastPage.getAsJsonArray("authorizedSenders").toString());
            assertEquals(9, lastPage.get("senderTotalCount").getAsInt());
            assertFalse(follower.statusSnapshot().has("authorizedSenders"));
            assertFalse(follower.statusSnapshot().has("senderLastUsedAt"));

            JsonObject revokePayload = new JsonObject();
            revokePayload.addProperty("senderId", "fixture-sender-0");
            follower.control("REVOKE_SENDER", revokePayload);
            assertEquals(8, follower.statusSnapshot().get("senderAccessCount").getAsInt());
            JsonObject remaining = follower.control("LIST_SENDERS", new JsonObject());
            assertEquals(8, remaining.getAsJsonArray("authorizedSenders").size());
            assertFalse(remaining.getAsJsonArray("authorizedSenders").toString().contains("fixture-sender-0"));
        } finally {
            follower.stop();
            leader.stop();
        }
    }

    private static ClawBotBindingHandoff testHandoff() {
        return new ClawBotBindingHandoff(
                new ClawBotCredentialStore(new FakeCredentialBackend()),
                new ClawBotBindingMetadataStore(new FakeMetadataBackend()));
    }

    private static JsonObject inboundJson(String messageId) {
        JsonObject inbound = new JsonObject();
        inbound.addProperty("messageId", messageId);
        inbound.addProperty("fromUserId", "sender");
        inbound.addProperty("contextToken", "context");
        inbound.addProperty("text", "text");
        return inbound;
    }

    private static ClawBotSessionRegistry sessionRegistry(ClawBotGatewayRuntimeService service)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = ClawBotGatewayRuntimeService.class.getDeclaredField("sessionRegistry");
        field.setAccessible(true);
        return (ClawBotSessionRegistry) field.get(service);
    }

    private static ClawBotSessionRegistration registration(ClawBotSessionStatus status) {
        return new ClawBotSessionRegistration(
                "session-1",
                "ide-instance",
                "project-1",
                "Demo Project",
                "codex",
                Set.of("STATUS", "CONTINUE"),
                status,
                4);
    }

    private static final class FakeCredentialBackend implements ClawBotCredentialStore.SecretBackend {

        private final Map<String, String> values = new HashMap<>();

        @Override
        public String read(String key) {
            return values.get(key);
        }

        @Override
        public void write(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void clear(String key) {
            values.remove(key);
        }
    }

    private static final class FakeMetadataBackend implements ClawBotBindingMetadataStore.MetadataBackend {

        private final Map<String, String> values = new HashMap<>();

        @Override
        public String read(String key) {
            return values.get(key);
        }

        @Override
        public void write(String key, String value) {
            values.put(key, value);
        }
    }
}
