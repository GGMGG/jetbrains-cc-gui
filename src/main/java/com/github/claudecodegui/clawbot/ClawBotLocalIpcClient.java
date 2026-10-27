package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.github.claudecodegui.clawbot.ClawBotIpcAuthentication.number;
import static com.github.claudecodegui.clawbot.ClawBotIpcAuthentication.text;

/** A mutually authenticated, correlated transaction. The secret never appears in a packet. */
public final class ClawBotLocalIpcClient {
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
            task -> ClawBotLocalIpcServer.daemon(task, "Claw IPC client deadline"));
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private final ClawBotLocalIpcServer.Endpoint endpoint;
    private final String instanceId;
    private final long connectionEpoch;
    private final int connectTimeoutMillis;
    private final int socketTimeoutMillis;

    public ClawBotLocalIpcClient(ClawBotLocalIpcServer.Endpoint endpoint, String instanceId, long connectionEpoch) {
        this(endpoint, instanceId, connectionEpoch, 5_000, 10_000);
    }

    ClawBotLocalIpcClient(ClawBotLocalIpcServer.Endpoint endpoint, String instanceId, long connectionEpoch, int connectTimeoutMillis, int socketTimeoutMillis) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (instanceId == null || instanceId.isBlank() || instanceId.length() > 256 || instanceId.chars().anyMatch(Character::isISOControl)
                || connectionEpoch < 0 || connectTimeoutMillis < 1 || socketTimeoutMillis < 1) {
            throw new IllegalArgumentException("Invalid IPC client identity or deadline");
        }
        this.instanceId = instanceId;
        this.connectionEpoch = connectionEpoch;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.socketTimeoutMillis = socketTimeoutMillis;
    }

    public ClawBotIpcEnvelope request(ClawBotIpcEnvelope request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (!instanceId.equals(request.instanceId()) || connectionEpoch != request.connectionEpoch()) {
            throw new IOException("CLAWBOT_IPC_OWNER_MISMATCH");
        }
        // Resolve only literal loopback addresses; a discovery record cannot trigger arbitrary DNS.
        if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(endpoint.host())) {
            throw new IOException("CLAWBOT_IPC_NON_LOOPBACK_ENDPOINT");
        }
        try (Socket socket = new Socket()) {
            var deadline = DEADLINES.schedule(() -> ClawBotLocalIpcServer.closeSocket(socket), socketTimeoutMillis, TimeUnit.MILLISECONDS);
            try {
                socket.connect(new InetSocketAddress(InetAddress.getByName(endpoint.host()), endpoint.port()), connectTimeoutMillis);
                socket.setSoTimeout(socketTimeoutMillis);
                JsonObject hello = new JsonObject();
                hello.addProperty("protocolVersion", ClawBotIpcEnvelope.PROTOCOL_VERSION);
                hello.addProperty("type", "GATEWAY_HELLO");
                hello.addProperty("requestId", UUID.randomUUID().toString());
                hello.addProperty("clientInstanceId", instanceId);
                hello.addProperty("clientConnectionEpoch", connectionEpoch);
                write(socket, hello);
                JsonObject challenge = read(socket);
                if (!"GATEWAY_CHALLENGE".equals(text(challenge, "type")) || number(challenge, "protocolVersion") != ClawBotIpcEnvelope.PROTOCOL_VERSION
                        || !text(challenge, "requestId").equals(text(hello, "requestId"))
                        || !ClawBotIpcAuthentication.verify(ClawBotIpcAuthentication.proof(endpoint.authToken(), "server", endpoint.port(), hello, challenge), challenge)) {
                    throw new IOException("CLAWBOT_IPC_AUTH_FAILED");
                }
                JsonObject authorization = new JsonObject();
                authorization.addProperty("type", "GATEWAY_AUTH");
                authorization.addProperty("requestId", text(hello, "requestId"));
                authorization.addProperty("serverNonce", text(challenge, "serverNonce"));
                authorization.addProperty("authProof", ClawBotIpcAuthentication.proof(endpoint.authToken(), "client", endpoint.port(), hello, challenge));
                write(socket, authorization);
                JsonObject welcome = read(socket);
                if (!"GATEWAY_WELCOME".equals(text(welcome, "type")) || number(welcome, "protocolVersion") != ClawBotIpcEnvelope.PROTOCOL_VERSION
                        || !text(welcome, "requestId").equals(text(hello, "requestId"))
                        || !text(welcome, "serverNonce").equals(text(challenge, "serverNonce"))
                        || !text(welcome, "instanceId").equals(text(challenge, "instanceId"))
                        || number(welcome, "connectionEpoch") != number(challenge, "connectionEpoch")) {
                    throw new IOException("CLAWBOT_IPC_HANDSHAKE_INVALID");
                }
                write(socket, request.toJson());
                ClawBotIpcEnvelope response = ClawBotIpcEnvelope.fromJson(read(socket));
                if (!response.requestId().equals(request.requestId()) || !response.instanceId().equals(text(challenge, "instanceId"))
                        || response.connectionEpoch() != number(challenge, "connectionEpoch")) {
                    throw new IOException("CLAWBOT_IPC_REPLY_INVALID");
                }
                return response;
            } catch (IllegalArgumentException invalid) {
                throw new IOException("CLAWBOT_IPC_ENVELOPE_INVALID");
            } finally {
                deadline.cancel(false);
            }
        }
    }

    private static void write(Socket socket, JsonObject value) throws IOException {
        ClawBotIpcFrameCodec.write(socket.getOutputStream(), value, ClawBotIpcFrameCodec.DEFAULT_MAX_FRAME_BYTES);
    }

    private static JsonObject read(Socket socket) throws IOException {
        JsonObject value = ClawBotIpcFrameCodec.read(socket.getInputStream(), ClawBotIpcFrameCodec.DEFAULT_MAX_FRAME_BYTES);
        if ("GATEWAY_REJECTED".equals(text(value, "type"))) {
            String code = text(value, "code");
            throw new IOException(code.matches("CLAWBOT_[A-Z_]{1,64}") ? code : "CLAWBOT_IPC_REJECTED");
        }
        return value;
    }
}
