package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.github.claudecodegui.clawbot.ClawBotIpcAuthentication.number;
import static com.github.claudecodegui.clawbot.ClawBotIpcAuthentication.text;

/** One authenticated request per loopback socket, with bounded workers and an absolute deadline. */
public final class ClawBotLocalIpcServer implements AutoCloseable {
    static final int PROTOCOL_VERSION = ClawBotIpcEnvelope.PROTOCOL_VERSION;
    static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_TOKEN_LENGTH = 256;
    private final String instanceId;
    private final long connectionEpoch;
    private final String authToken;
    private final Function<ClawBotIpcEnvelope, ClawBotIpcEnvelope> handler;
    private final int maxFrameBytes;
    private final int socketTimeoutMillis;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> daemon(task, "Claw IPC worker"));
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> daemon(task, "Claw IPC deadline"));
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private boolean closed;
    private ServerSocket listener;
    private Endpoint endpoint;

    public ClawBotLocalIpcServer(String instanceId, long connectionEpoch, Function<ClawBotIpcEnvelope, ClawBotIpcEnvelope> handler) {
        this(instanceId, connectionEpoch, createAuthToken(), handler);
    }

    public ClawBotLocalIpcServer(String instanceId, long connectionEpoch, String authToken, Function<ClawBotIpcEnvelope, ClawBotIpcEnvelope> handler) {
        this(instanceId, connectionEpoch, authToken, handler, ClawBotIpcFrameCodec.DEFAULT_MAX_FRAME_BYTES, DEFAULT_SOCKET_TIMEOUT_MILLIS);
    }

    ClawBotLocalIpcServer(String instanceId, long connectionEpoch, String authToken,
            Function<ClawBotIpcEnvelope, ClawBotIpcEnvelope> handler, int maxFrameBytes, int socketTimeoutMillis) {
        this.instanceId = requireValue(instanceId, "instanceId", ClawBotIpcEnvelope.MAX_IDENTIFIER_LENGTH);
        this.authToken = requireValue(authToken, "authToken", MAX_TOKEN_LENGTH);
        if (connectionEpoch < 0 || maxFrameBytes < 1 || maxFrameBytes > ClawBotIpcFrameCodec.DEFAULT_MAX_FRAME_BYTES || socketTimeoutMillis < 1) {
            throw new IllegalArgumentException("Invalid IPC limits");
        }
        this.connectionEpoch = connectionEpoch;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.maxFrameBytes = maxFrameBytes;
        this.socketTimeoutMillis = socketTimeoutMillis;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public synchronized Endpoint start() throws IOException {
        if (closed) {
            throw new IllegalStateException("CLAWBOT_IPC_SERVER_CLOSED");
        }
        if (endpoint != null) {
            return endpoint;
        }
        listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        } catch (IOException | RuntimeException failure) {
            listener.close();
            throw failure;
        }
        endpoint = new Endpoint("127.0.0.1", listener.getLocalPort(), PROTOCOL_VERSION, authToken);
        running = true;
        daemon(this::accept, "Claw IPC accept").start();
        return endpoint;
    }

    private void accept() {
        while (running) {
            Socket socket = null;
            ScheduledFuture<?> deadline = null;
            try {
                socket = listener.accept();
                if (!running || !socket.getInetAddress().isLoopbackAddress()) {
                    closeSocket(socket);
                    continue;
                }
                sockets.add(socket);
                Socket accepted = socket;
                deadline = deadlines.schedule(() -> closeSocket(accepted), socketTimeoutMillis, TimeUnit.MILLISECONDS);
                ScheduledFuture<?> timeout = deadline;
                workers.execute(() -> serve(accepted, timeout));
            } catch (IOException | RuntimeException rejected) {
                if (deadline != null) {
                    deadline.cancel(false);
                }
                if (socket != null) {
                    sockets.remove(socket);
                    closeSocket(socket);
                }
                if (!running || listener.isClosed()) {
                    return;
                }
            }
        }
    }

    private void serve(Socket socket, ScheduledFuture<?> deadline) {
        try (socket) {
            socket.setSoTimeout(socketTimeoutMillis);
            JsonObject hello = read(socket);
            if (number(hello, "protocolVersion") != PROTOCOL_VERSION || !"GATEWAY_HELLO".equals(text(hello, "type"))) {
                reject(socket, "CLAWBOT_IPC_PROTOCOL_UNSUPPORTED");
                return;
            }
            String nonce = text(hello, "requestId");
            String clientId = text(hello, "clientInstanceId");
            long clientEpoch = number(hello, "clientConnectionEpoch");
            JsonObject challenge = new JsonObject();
            challenge.addProperty("protocolVersion", PROTOCOL_VERSION);
            challenge.addProperty("type", "GATEWAY_CHALLENGE");
            challenge.addProperty("requestId", nonce);
            challenge.addProperty("instanceId", instanceId);
            challenge.addProperty("connectionEpoch", connectionEpoch);
            challenge.addProperty("serverNonce", UUID.randomUUID().toString());
            challenge.addProperty("authProof", ClawBotIpcAuthentication.proof(authToken, "server", endpoint.port(), hello, challenge));
            write(socket, challenge);
            JsonObject authorization = read(socket);
            if (!"GATEWAY_AUTH".equals(text(authorization, "type")) || !nonce.equals(text(authorization, "requestId"))
                    || !text(challenge, "serverNonce").equals(text(authorization, "serverNonce"))
                    || !ClawBotIpcAuthentication.verify(ClawBotIpcAuthentication.proof(authToken, "client", endpoint.port(), hello, challenge), authorization)) {
                reject(socket, "CLAWBOT_IPC_AUTH_FAILED");
                return;
            }
            JsonObject welcome = challenge.deepCopy();
            welcome.addProperty("type", "GATEWAY_WELCOME");
            welcome.remove("authProof");
            write(socket, welcome);
            ClawBotIpcEnvelope request = ClawBotIpcEnvelope.fromJson(read(socket));
            if (!clientId.equals(request.instanceId()) || clientEpoch != request.connectionEpoch() || !running || socket.isClosed()) {
                reject(socket, "CLAWBOT_IPC_OWNER_MISMATCH");
                return;
            }
            ClawBotIpcEnvelope response = Objects.requireNonNull(handler.apply(request), "handler response");
            if (!response.requestId().equals(request.requestId()) || !response.instanceId().equals(instanceId) || response.connectionEpoch() != connectionEpoch) {
                reject(socket, "CLAWBOT_IPC_REPLY_INVALID");
                return;
            }
            write(socket, response.toJson());
        } catch (IOException | RuntimeException ignored) {
            // Packet bodies, secrets and peer-controlled exception text must not reach logs.
        } finally {
            deadline.cancel(false);
            sockets.remove(socket);
        }
    }

    private JsonObject read(Socket socket) throws IOException { return ClawBotIpcFrameCodec.read(socket.getInputStream(), maxFrameBytes); }
    private void write(Socket socket, JsonObject value) throws IOException { ClawBotIpcFrameCodec.write(socket.getOutputStream(), value, maxFrameBytes); }

    private void reject(Socket socket, String code) throws IOException {
        JsonObject rejected = new JsonObject();
        rejected.addProperty("protocolVersion", PROTOCOL_VERSION);
        rejected.addProperty("type", "GATEWAY_REJECTED");
        rejected.addProperty("code", code);
        write(socket, rejected);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        running = false;
        if (listener != null) {
            try { listener.close(); } catch (IOException ignored) { }
        }
        sockets.forEach(ClawBotLocalIpcServer::closeSocket);
        workers.shutdownNow();
        deadlines.shutdownNow();
    }

    static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static String createAuthToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String requireValue(String value, String name, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    /** Immutable endpoint descriptor; its string form intentionally omits the authentication token. */
    public static final class Endpoint {
        private final String host;
        private final int port;
        private final int protocolVersion;
        private final String authToken;

        public Endpoint(String host, int port, int protocolVersion, String authToken) {
            this.host = requireValue(host, "host", 128);
            this.port = port;
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid port");
            }
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IllegalArgumentException("CLAWBOT_IPC_PROTOCOL_UNSUPPORTED");
            }
            this.protocolVersion = protocolVersion;
            this.authToken = requireValue(authToken, "authToken", MAX_TOKEN_LENGTH);
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public int protocolVersion() {
            return protocolVersion;
        }

        String authToken() {
            return authToken;
        }

        @Override
        public String toString() {
            return "Endpoint{" + host + ':' + port + ", protocolVersion=" + protocolVersion + '}';
        }
    }
}
