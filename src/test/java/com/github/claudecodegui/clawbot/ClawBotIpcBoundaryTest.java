package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ClawBotIpcBoundaryTest {
    @Test
    public void aSpoofedCoordinatorCannotObtainTheSecretOrAnAuthorizationProof() throws Exception {
        var captured = new AtomicReference<JsonObject>();
        var failed = new AtomicReference<Throwable>();
        try (var listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var endpoint = new ClawBotLocalIpcServer.Endpoint("127.0.0.1", listener.getLocalPort(), ClawBotIpcEnvelope.PROTOCOL_VERSION, "fixture-secret");
            Thread fake = ClawBotLocalIpcServer.daemon(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    JsonObject hello = read(socket); captured.set(hello);
                    JsonObject challenge = challenge(hello);
                    challenge.addProperty("authProof", ClawBotIpcAuthentication.proof(endpoint.authToken(), "client", endpoint.port(), hello, challenge));
                    write(socket, challenge);
                    assertEquals(-1, socket.getInputStream().read());
                } catch (Throwable error) { failed.set(error); }
            }, "Claw fake coordinator fixture");
            fake.start();
            var failure = assertThrows(IOException.class, () -> new ClawBotLocalIpcClient(endpoint, "idea", 1).request(request("idea", 1)));
            assertEquals("CLAWBOT_IPC_AUTH_FAILED", failure.getMessage());
            fake.join(3000); assertFalse(fake.isAlive()); assertNull(failed.get());
            assertNotNull(captured.get()); assertFalse(captured.get().has("authToken")); assertFalse(captured.get().has("authProof"));
            assertFalse(captured.get().toString().contains(endpoint.authToken()));
        }
    }

    @Test
    public void authenticatedReplyStillMustMatchTheRequestAndCoordinatorIdentity() throws Exception {
        var failed = new AtomicReference<Throwable>();
        try (var listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var endpoint = new ClawBotLocalIpcServer.Endpoint("127.0.0.1", listener.getLocalPort(), ClawBotIpcEnvelope.PROTOCOL_VERSION, "fixture-secret");
            Thread fake = ClawBotLocalIpcServer.daemon(() -> {
                try (Socket socket = listener.accept()) {
                    JsonObject hello = read(socket); JsonObject challenge = challenge(hello);
                    challenge.addProperty("authProof", ClawBotIpcAuthentication.proof(endpoint.authToken(), "server", endpoint.port(), hello, challenge));
                    write(socket, challenge); read(socket);
                    challenge.addProperty("type", "GATEWAY_WELCOME"); challenge.remove("authProof"); write(socket, challenge);
                    var request = ClawBotIpcEnvelope.fromJson(read(socket));
                    write(socket, new ClawBotIpcEnvelope(ClawBotIpcEnvelope.PROTOCOL_VERSION, "PONG", request.requestId() + "-stale", "coordinator", 7, new JsonObject()).toJson());
                } catch (Throwable error) { failed.set(error); }
            }, "Claw stale response fixture");
            fake.start();
            var failure = assertThrows(IOException.class, () -> new ClawBotLocalIpcClient(endpoint, "idea", 1).request(request("idea", 1)));
            assertEquals("CLAWBOT_IPC_REPLY_INVALID", failure.getMessage());
            fake.join(3000); assertFalse(fake.isAlive()); assertNull(failed.get());
        }
    }

    @Test
    public void replayedAuthorizationCannotAuthorizeTheNextSocketChallenge() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        try (var server = new ClawBotLocalIpcServer("coordinator", 7, request -> {
            handled.incrementAndGet();
            return new ClawBotIpcEnvelope(ClawBotIpcEnvelope.PROTOCOL_VERSION, "PONG", request.requestId(), "coordinator", 7, new JsonObject());
        })) {
            var endpoint = server.start();
            JsonObject hello = new JsonObject(); hello.addProperty("protocolVersion", ClawBotIpcEnvelope.PROTOCOL_VERSION);
            hello.addProperty("type", "GATEWAY_HELLO"); hello.addProperty("requestId", UUID.randomUUID().toString());
            hello.addProperty("clientInstanceId", "idea"); hello.addProperty("clientConnectionEpoch", 1);
            JsonObject authorization = new JsonObject();
            try (var socket = new Socket(endpoint.host(), endpoint.port())) {
                write(socket, hello); var challenge = read(socket);
                authorization.addProperty("type", "GATEWAY_AUTH"); authorization.addProperty("requestId", hello.get("requestId").getAsString());
                authorization.addProperty("serverNonce", challenge.get("serverNonce").getAsString());
                authorization.addProperty("authProof", ClawBotIpcAuthentication.proof(endpoint.authToken(), "client", endpoint.port(), hello, challenge));
                write(socket, authorization); assertEquals("GATEWAY_WELCOME", read(socket).get("type").getAsString());
                write(socket, request("idea", 1).toJson()); assertEquals("PONG", read(socket).get("type").getAsString());
            }
            try (var socket = new Socket(endpoint.host(), endpoint.port())) {
                write(socket, hello); var challenge = read(socket);
                assertNotEquals(authorization.get("serverNonce"), challenge.get("serverNonce"));
                write(socket, authorization);
                assertEquals("CLAWBOT_IPC_AUTH_FAILED", read(socket).get("code").getAsString());
            }
            assertEquals(1, handled.get());
        }
    }

    @Test
    public void slowBytesCannotExtendTheDeadlineAndCloseInterruptsAnAcceptedSocket() throws Exception {
        try (var server = new ClawBotLocalIpcServer("coordinator", 7, "fixture-secret", request -> request, 65536, 150)) {
            var endpoint = server.start();
            try (var socket = new Socket(endpoint.host(), endpoint.port())) {
                socket.setSoTimeout(2000); new DataOutputStream(socket.getOutputStream()).writeInt(1000);
                long start = System.nanoTime();
                for (int i = 0; i < 10; i++) {
                    try { socket.getOutputStream().write(' '); } catch (IOException closed) { break; }
                    Thread.sleep(25);
                }
                assertClosed(socket);
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
            }
        }
        try (var server = new ClawBotLocalIpcServer("coordinator", 7, request -> request)) {
            var endpoint = server.start();
            try (var socket = new Socket(endpoint.host(), endpoint.port())) {
                socket.setSoTimeout(2000); socket.getOutputStream().write(0); server.close(); assertClosed(socket);
            }
        }
    }

    @Test
    public void framesRejectMalformedUtf8DuplicateKeysAndExcessiveNesting() throws Exception {
        for (byte[] invalid : new byte[][] {"{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8),
                ("{\"a\":".repeat(40) + "0" + "}".repeat(40)).getBytes(StandardCharsets.UTF_8),
                new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xC3, 0x28, '"', '}'} }) {
            var output = new ByteArrayOutputStream(); var data = new DataOutputStream(output); data.writeInt(invalid.length); data.write(invalid);
            assertThrows(IOException.class, () -> ClawBotIpcFrameCodec.read(new ByteArrayInputStream(output.toByteArray()), 65536));
        }
    }

    private static JsonObject challenge(JsonObject hello) {
        JsonObject value = new JsonObject(); value.addProperty("protocolVersion", ClawBotIpcEnvelope.PROTOCOL_VERSION);
        value.addProperty("type", "GATEWAY_CHALLENGE"); value.add("requestId", hello.get("requestId"));
        value.addProperty("instanceId", "coordinator"); value.addProperty("connectionEpoch", 7); value.addProperty("serverNonce", UUID.randomUUID().toString());
        return value;
    }
    private static ClawBotIpcEnvelope request(String instance, long epoch) {
        return new ClawBotIpcEnvelope(ClawBotIpcEnvelope.PROTOCOL_VERSION, "PING", UUID.randomUUID().toString(), instance, epoch, new JsonObject());
    }
    private static void write(Socket socket, JsonObject value) throws IOException { ClawBotIpcFrameCodec.write(socket.getOutputStream(), value, 65536); }
    private static JsonObject read(Socket socket) throws IOException { return ClawBotIpcFrameCodec.read(socket.getInputStream(), 65536); }
    private static void assertClosed(Socket socket) throws IOException {
        try { assertEquals(-1, socket.getInputStream().read()); } catch (SocketException reset) { assertNotNull(reset.getMessage()); }
    }
}
