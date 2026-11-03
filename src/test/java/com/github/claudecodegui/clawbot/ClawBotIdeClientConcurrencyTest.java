package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class ClawBotIdeClientConcurrencyTest {
    @Parameterized.Parameters(name = "{0}")
    public static List<String> deliveries() {
        return List.of("SESSION_PREVIEW_REPLY", "SESSION_REPLY", "SESSION_REJECT", "SESSION_CONTROL_REPLY",
                "SESSION_CONTROL_REJECT", "SESSION_LOCAL_INTERACTION", "SESSION_PROGRESS", "SEND_TEXT");
    }

    private final String deliveryType;

    public ClawBotIdeClientConcurrencyTest(String deliveryType) {
        this.deliveryType = deliveryType;
    }

    @Test
    public void slowDeliveryDoesNotBlockHeartbeatControlPollingOrClose() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (var server = new ClawBotLocalIpcServer("gateway", 9, request -> {
            received.add(request.type());
            if (deliveryType.equals(request.type())) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Delivery release timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            return response(request, false);
        }); var client = new ClawBotIdeClient(() -> server.start(), "idea", 1, 60_000)) {
            assertTrue(client.register(registration()));
            var delivery = workers.submit(() -> deliver(client));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                workers.submit(() -> {
                    assertTrue(client.heartbeat("tab", ClawBotSessionStatus.BUSY));
                    assertNull(client.pollCommand("tab"));
                    assertNull(client.pollInbound("tab"));
                    assertNull(client.pollPreview("tab"));
                    assertTrue(client.updateInteraction("tab", "message", "question"));
                    client.close();
                    return true;
                }).get(3, TimeUnit.SECONDS);
                assertFalse(received.contains("SESSION_UNREGISTER"));
                assertThrows(IllegalStateException.class, () -> client.pollCommand("tab"));
            } finally {
                release.countDown();
            }
            assertCancelled(delivery);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void closeCancelsBothSlowDeliveryAndConcurrentControlTransport() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch deliveryEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try (var server = new ClawBotLocalIpcServer("gateway", 9, request -> {
            if (deliveryType.equals(request.type())) {
                deliveryEntered.countDown();
            }
            if (deliveryType.equals(request.type()) || "SESSION_HEARTBEAT".equals(request.type())) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Transport release timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            return response(request, false);
        }); var client = new ClawBotIdeClient(() -> server.start(), "idea", 1, 60_000)) {
            assertTrue(client.register(registration()));
            var delivery = workers.submit(() -> deliver(client));
            assertTrue(deliveryEntered.await(5, TimeUnit.SECONDS));
            var heartbeat = workers.submit(() -> client.heartbeat("tab", ClawBotSessionStatus.BUSY));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            workers.submit(() -> {
                client.close();
                return true;
            }).get(3, TimeUnit.SECONDS);

            assertCancelled(delivery);
            assertCancelled(heartbeat);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private static void assertCancelled(java.util.concurrent.Future<Boolean> pending) {
        var failure = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IOException);
    }

    @Test
    public void deliveryOwnershipFailureStillRestoresSnapshotBeforeRetry() throws Exception {
        AtomicBoolean failDelivery = new AtomicBoolean(true);
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        try (var server = new ClawBotLocalIpcServer("gateway", 9, request -> {
            if ("SESSION_SNAPSHOT".equals(request.type())) {
                snapshots.incrementAndGet();
            }
            return response(request, deliveryType.equals(request.type()) && failDelivery.getAndSet(false));
        }); var client = new ClawBotIdeClient(() -> server.start(), "idea", 1, 60_000)) {
            client.setSessionRecoveryListener(recoveries::incrementAndGet);
            assertTrue(client.register(registration()));
            assertEquals("CLAWBOT_SESSION_NOT_OWNER", assertThrows(IOException.class, () -> deliver(client)).getMessage());

            assertTrue(deliver(client));
            assertEquals(1, snapshots.get());
            assertEquals(1, recoveries.get());
            assertTrue(client.heartbeat("tab", ClawBotSessionStatus.ONLINE));
            assertEquals(1, snapshots.get());
        }
    }

    private boolean deliver(ClawBotIdeClient client) throws IOException {
        return switch (deliveryType) {
            case "SESSION_PREVIEW_REPLY" -> client.replyToPreview("tab", "message", "Preview");
            case "SESSION_REPLY" -> client.replyToInbound("tab", "message", "Answer");
            case "SESSION_REJECT" -> client.rejectInbound("tab", "message", "Rejected");
            case "SESSION_CONTROL_REPLY" -> client.replyToCommand("tab", "message", "Stopped");
            case "SESSION_CONTROL_REJECT" -> client.rejectCommand("tab", "message", "Rejected");
            case "SESSION_LOCAL_INTERACTION" -> client.publishLocalInteraction("tab", "generation", "question", "Choose A");
            case "SESSION_PROGRESS" -> client.sendProgress("tab", "message", "event", "Progress");
            case "SEND_TEXT" -> {
                client.sendText("tab", "sender", "context", "Text");
                yield true;
            }
            default -> throw new AssertionError(deliveryType);
        };
    }

    private static ClawBotIpcEnvelope response(ClawBotIpcEnvelope request, boolean error) {
        JsonObject result = new JsonObject();
        result.addProperty("accepted", true);
        if (error) {
            result.addProperty("code", "CLAWBOT_SESSION_NOT_OWNER");
        }
        return new ClawBotIpcEnvelope(ClawBotIpcEnvelope.PROTOCOL_VERSION,
                error ? "ERROR" : request.type() + "_RESULT", request.requestId(), "gateway", 9, result);
    }

    private static ClawBotSessionRegistration registration() {
        return new ClawBotSessionRegistration("tab", "idea", "project", "Project", "codex", Set.of("CONTROL"),
                ClawBotSessionStatus.ONLINE, 1, "Tab", "generation");
    }
}
