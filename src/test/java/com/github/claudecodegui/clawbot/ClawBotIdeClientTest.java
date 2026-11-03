package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;
import org.junit.Test;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ClawBotIdeClientTest {
    @Test
    public void reconnectReplacesTheWholeOwnedSnapshotAndNotifiesRecoveryOnce() throws Exception {
        var registry = new AtomicReference<>(new ClawBotSessionRegistry());
        var recovered = new AtomicInteger();
        try (var server = server(request -> handle(registry.get(), request))) {
            try (var client = new ClawBotIdeClient(server.start(), "idea", 1)) {
                client.setSessionRecoveryListener(recovered::incrementAndGet);
                assertTrue(client.register(registration("a", "idea", 1)));
                assertTrue(client.register(registration("b", "idea", 1)));
                registry.set(new ClawBotSessionRegistry());
                assertTrue(client.heartbeat("a", ClawBotSessionStatus.ONLINE));
                assertEquals(2, client.listSessions().size()); assertEquals(1, recovered.get());
                assertTrue(client.heartbeat("a", ClawBotSessionStatus.ONLINE)); assertEquals(1, recovered.get());
                assertTrue(client.unregister("a")); assertEquals(1, client.listSessions().size());
            }
        }
    }

    @Test
    public void anOldClientCannotRecoverOverTheNewOwnerEpoch() throws Exception {
        var registry = new ClawBotSessionRegistry();
        try (var server = server(request -> handle(registry, request))) {
            var endpoint = server.start();
            try (var old = new ClawBotIdeClient(endpoint, "idea", 1); var latest = new ClawBotIdeClient(endpoint, "pycharm", 2)) {
                assertTrue(old.register(registration("tab", "idea", 1)));
                assertFalse(latest.register(registration("tab", "pycharm", 2)));
                assertTrue(registry.unregister("tab", "idea", 1));
                assertTrue(latest.register(registration("tab", "pycharm", 2)));
                assertFalse(old.heartbeat("tab", ClawBotSessionStatus.ONLINE));
                assertEquals("CLAWBOT_SESSION_SNAPSHOT_RECOVERY_FAILED",
                        assertThrows(IOException.class, () -> old.unregister("tab")).getMessage());
                assertEquals("pycharm", registry.snapshot().get(0).instanceId());
            }
        }
    }

    @Test
    public void invalidAcceptedTypeAndWrongResponseActionNeverConfirmRegistration() throws Exception {
        for (boolean wrongAction : List.of(false, true)) {
            try (var server = server(request -> {
                JsonObject payload = new JsonObject(); payload.addProperty("accepted", "true");
                return response(request, wrongAction ? "SESSION_UNREGISTER_RESULT" : "SESSION_REGISTER_RESULT", payload);
            }); var client = new ClawBotIdeClient(server.start(), "idea", 1)) {
                var failure = assertThrows(IOException.class, () -> client.register(registration("a", "idea", 1)));
                assertEquals("CLAWBOT_IPC_RESULT_INVALID", failure.getMessage());
            }
        }
    }

    @Test
    public void typedTransportFailuresArePreservedWithoutAcceptingProviderText() throws Exception {
        for (String code : List.of("ILINK_SEND_REJECTED", "ILINK_SEND_RATE_LIMITED", "ILINK_SEND_RESULT_UNKNOWN", "provider text", "ILINK_bad")) {
            try (var server = server(request -> {
                JsonObject payload = new JsonObject();
                payload.addProperty("code", code);
                return response(request, "ERROR", payload);
            }); var client = new ClawBotIdeClient(server.start(), "idea", 1)) {
                String expected = code.matches("ILINK_[A-Z_]+") ? code : "CLAWBOT_IPC_RESULT_INVALID";
                assertEquals(expected, assertThrows(IOException.class, () -> client.register(registration("a", "idea", 1))).getMessage());
            }
        }
    }

    @Test
    public void duplicateSnapshotsAreRejectedBeforeAnyNetworkDispatch() throws Exception {
        var requests = new AtomicInteger();
        try (var server = server(request -> { requests.incrementAndGet(); return handle(new ClawBotSessionRegistry(), request); });
             var client = new ClawBotIdeClient(server.start(), "idea", 1)) {
            var registration = registration("a", "idea", 1);
            assertThrows(IllegalArgumentException.class, () -> client.replaceSnapshot(List.of(registration, registration)));
            assertEquals(0, requests.get());
        }
    }

    @Test
    public void closeInterruptsBlockedIoWithoutWaitingOrSendingLateCleanup() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var requests = new AtomicInteger(); var worker = Executors.newSingleThreadExecutor();
        try (var server = server(request -> {
            requests.incrementAndGet(); entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            JsonObject payload = new JsonObject(); payload.addProperty("accepted", true);
            return response(request, "SESSION_REGISTER_RESULT", payload);
        }); var client = new ClawBotIdeClient(server.start(), "idea", 1)) {
            var pending = worker.submit(() -> {
                try { client.register(registration("a", "idea", 1)); return false; } catch (IOException closed) { return true; }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long began = System.nanoTime(); client.close();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 500);
            assertTrue(pending.get(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> client.register(registration("b", "idea", 1)));
            release.countDown(); assertEquals(1, requests.get());
        } finally {
            release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static ClawBotLocalIpcServer server(java.util.function.Function<ClawBotIpcEnvelope, ClawBotIpcEnvelope> handler) {
        return new ClawBotLocalIpcServer("coordinator", 9, handler);
    }

    private static ClawBotIpcEnvelope handle(ClawBotSessionRegistry registry, ClawBotIpcEnvelope request) {
        try {
            JsonObject input = request.payload(); JsonObject result = new JsonObject(); boolean accepted;
            switch (request.type()) {
            case "SESSION_REGISTER": accepted = registry.register(ClawBotSessionWireCodec.registrationFromJson(input.getAsJsonObject("session"))); break;
            case "SESSION_SNAPSHOT": accepted = registry.replaceSnapshot(request.instanceId(), request.connectionEpoch(), ClawBotSessionWireCodec.registrationsFromJson(input.get("sessions"))); break;
            case "SESSION_HEARTBEAT": accepted = registry.heartbeat(input.get("sessionHandleId").getAsString(), request.instanceId(), request.connectionEpoch(), ClawBotSessionStatus.ONLINE); break;
            case "SESSION_UNREGISTER": accepted = registry.unregister(input.get("sessionHandleId").getAsString(), request.instanceId(), request.connectionEpoch()); break;
            case "LIST_SESSIONS": result.add("sessions", ClawBotSessionWireCodec.snapshotsToJson(registry.snapshot())); return response(request, "SESSION_LIST_RESULT", result);
            default: throw new AssertionError("Unexpected fixture operation");
            }
            result.addProperty("accepted", accepted); return response(request, request.type() + "_RESULT", result);
        } catch (IOException failure) { throw new AssertionError(failure); }
    }

    private static ClawBotIpcEnvelope response(ClawBotIpcEnvelope request, String type, JsonObject payload) {
        return new ClawBotIpcEnvelope(ClawBotIpcEnvelope.PROTOCOL_VERSION, type, request.requestId(), "coordinator", 9, payload);
    }

    private static ClawBotSessionRegistration registration(String handle, String owner, long epoch) {
        return new ClawBotSessionRegistration(handle, owner, "project", "Project", "codex", Set.of("STATUS", "INBOUND"), ClawBotSessionStatus.ONLINE, epoch);
    }
}
