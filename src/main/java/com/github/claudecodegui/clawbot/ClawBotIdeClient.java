package com.github.claudecodegui.clawbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** IDE-side facade for session registration over authenticated local IPC. */
public final class ClawBotIdeClient implements AutoCloseable {

    static final long DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 10_000L;

    private final EndpointResolver endpointResolver;
    private final String instanceId;
    private final long connectionEpoch;
    private final long heartbeatIntervalMillis;
    private final Map<String, ClawBotSessionRegistration> registrations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor;
    private final Object requestLock = new Object();
    private volatile ScheduledFuture<?> heartbeatTask;
    private final java.util.concurrent.atomic.AtomicBoolean closeRequested = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile ClawBotLocalIpcClient activeTransport;
    private boolean snapshotPending;
    private boolean ownerRecoveryPending;
    private volatile Runnable sessionRecoveryListener = () -> { };
    private volatile boolean closed;

    public ClawBotIdeClient(
            ClawBotLocalIpcServer.Endpoint endpoint,
            String instanceId,
            long connectionEpoch
    ) {
        this(() -> endpoint, instanceId, connectionEpoch, DEFAULT_HEARTBEAT_INTERVAL_MILLIS);
    }

    ClawBotIdeClient(
            EndpointResolver endpointResolver,
            String instanceId,
            long connectionEpoch,
            long heartbeatIntervalMillis
    ) {
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
        this.instanceId = requireValue(instanceId, "instanceId");
        if (connectionEpoch < 0) {
            throw new IllegalArgumentException("Invalid connectionEpoch");
        }
        if (heartbeatIntervalMillis <= 0) {
            throw new IllegalArgumentException("Invalid heartbeatIntervalMillis");
        }
        this.connectionEpoch = connectionEpoch;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory("clawbot-heartbeat-"));
    }

    public boolean register(ClawBotSessionRegistration registration) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            requireOwned(registration);
            if (!registrations.containsKey(registration.sessionHandleId()) && registrations.size() >= 256) { return false; }
            JsonObject payload = new JsonObject();
            payload.add("session", ClawBotSessionWireCodec.registrationToJson(registration));
            boolean accepted = readAccepted(send("SESSION_REGISTER", payload));
            if (accepted) {
                registrations.put(registration.sessionHandleId(), registration);
                startHeartbeat();
            } else {
                snapshotPending = true;
                ownerRecoveryPending = true;
            }
            return accepted;
        }
    }

    public boolean heartbeat(String sessionHandleId, ClawBotSessionStatus status) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            ClawBotSessionRegistration registration = registrations.get(sessionHandleId);
            if (registration != null) {
                registrations.put(sessionHandleId, registration.withStatus(status));
            }
            if (snapshotPending) {
                if (!replaceSnapshotInternal(new ArrayList<>(registrations.values()))) {
                    return false;
                }
                snapshotPending = false;
                notifyOwnerRecovery();
            }
            if (heartbeatInternal(sessionHandleId, status)) {
                return true;
            }
            if (!snapshotPending || !replaceSnapshotInternal(new ArrayList<>(registrations.values()))) {
                return false;
            }
            snapshotPending = false;
            notifyOwnerRecovery();
            return heartbeatInternal(sessionHandleId, status);
        }
    }

    public boolean updatePresentation(String sessionHandleId, String provider, String tabDisplayName) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            ClawBotSessionRegistration current = registrations.get(sessionHandleId);
            if (current == null) {
                return false;
            }
            if (current.provider().equals(provider) && current.tabDisplayName().equals(tabDisplayName)) {
                return true;
            }
            ClawBotSessionRegistration updated = new ClawBotSessionRegistration(
                    current.sessionHandleId(), current.instanceId(), current.projectId(), current.projectDisplayName(),
                    provider, current.capabilities(), current.status(), current.connectionEpoch(), tabDisplayName,
                    current.generation());
            registrations.put(sessionHandleId, updated);
            return register(updated);
        }
    }

    public boolean updateSession(String sessionHandleId, String provider, String tabDisplayName, String generation,
            String activeTurnId) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            ClawBotSessionRegistration current = registrations.get(sessionHandleId);
            if (current == null) {
                return false;
            }
            ClawBotSessionStatus status = activeTurnId.isEmpty() ? ClawBotSessionStatus.ONLINE : ClawBotSessionStatus.BUSY;
            if (!current.provider().equals(provider) || !current.tabDisplayName().equals(tabDisplayName)
                    || !current.generation().equals(generation)) {
                ClawBotSessionRegistration updated = new ClawBotSessionRegistration(current.sessionHandleId(),
                        current.instanceId(), current.projectId(), current.projectDisplayName(), provider,
                        current.capabilities(), status,
                        current.connectionEpoch(), tabDisplayName, generation);
                registrations.put(sessionHandleId, updated);
                if (!register(updated)) {
                    return false;
                }
            }
            if (!heartbeat(sessionHandleId, status)) {
                return false;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionHandleId", sessionHandleId);
            payload.addProperty("generation", generation);
            payload.addProperty("activeTurnId", activeTurnId);
            return readAccepted(send("SESSION_ACTIVITY", payload));
        }
    }

    public boolean matchesTarget(String sessionHandleId, ClawBotInboundMessage message, String generation, String provider) {
        ClawBotSessionTarget target = message.target();
        return target != null && target.handle().equals(sessionHandleId) && target.instanceId().equals(instanceId)
                && target.connectionEpoch() == connectionEpoch && target.generation().equals(generation)
                && target.provider().equals(provider);
    }

    /** Registers a callback invoked once after automatic owner recovery completes. */
    public void setSessionRecoveryListener(Runnable listener) {
        sessionRecoveryListener = Objects.requireNonNull(listener, "listener");
    }

    public boolean validateInbound(String sessionHandleId, String messageId) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionHandleId", requireValue(sessionHandleId, "sessionHandleId"));
            payload.addProperty("messageId", requireBoundedValue(messageId, "messageId", ClawBotInboundMessage.MAX_MESSAGE_ID_LENGTH));
            return readAccepted(send("SESSION_VALIDATE", payload));
        }
    }

    public ClawBotInboundMessage pollPreview(String sessionHandleId) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionHandleId", requireValue(sessionHandleId, "sessionHandleId"));
            JsonObject result = send("SESSION_PREVIEW_POLL", payload);
            return result.has("message") && result.get("message").isJsonObject()
                    ? ClawBotInboundMessage.fromJson(result.getAsJsonObject("message")) : null;
        }
    }

    public boolean replyToPreview(String sessionHandleId, String messageId, String text) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionHandleId", requireValue(sessionHandleId, "sessionHandleId"));
            payload.addProperty("messageId", requireBoundedValue(messageId, "messageId", ClawBotInboundMessage.MAX_MESSAGE_ID_LENGTH));
            payload.addProperty("text", requireText(text));
            return readAccepted(send("SESSION_PREVIEW_REPLY", payload));
        }
    }

    public boolean unregister(String sessionHandleId) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            String safeSessionHandleId = requireValue(sessionHandleId, "sessionHandleId");
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionHandleId", safeSessionHandleId);
            payload.addProperty("instanceId", instanceId);
            payload.addProperty("connectionEpoch", connectionEpoch);
            boolean accepted = readAccepted(send("SESSION_UNREGISTER", payload));
            if (accepted) {
                registrations.remove(safeSessionHandleId);
            }
            return accepted;
        }
    }

    public boolean replaceSnapshot(List<ClawBotSessionRegistration> values) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            return replaceSnapshotInternal(values);
        }
    }

    public List<ClawBotSessionSnapshot> listSessions() throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = send("LIST_SESSIONS", new JsonObject());
            JsonArray sessions = requireArray(payload, "sessions");
            return ClawBotSessionWireCodec.snapshotsFromJson(sessions);
        }
    }

    /** Polls one queued inbound message for a registered session. */
    public ClawBotInboundMessage pollInbound(String sessionHandleId) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            String safeSessionHandleId = requireValue(sessionHandleId, "sessionHandleId");
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionHandleId", safeSessionHandleId);
            payload.addProperty("instanceId", instanceId);
            payload.addProperty("connectionEpoch", connectionEpoch);
            JsonObject result = send("SESSION_POLL", payload);
            if (!result.has("message") || result.get("message").isJsonNull()) {
                return null;
            }
            if (!result.get("message").isJsonObject()) {
                throw new IOException("CLAWBOT_IPC_RESULT_INVALID");
            }
            return ClawBotInboundMessage.fromJson(result.getAsJsonObject("message"));
        }
    }

    /** Sends the reply and confirms the queued inbound message in one gateway request. */
    public boolean replyToInbound(String sessionHandleId, String messageId, String text) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionHandleId", requireValue(sessionHandleId, "sessionHandleId"));
            payload.addProperty("messageId", requireBoundedValue(
                    messageId, "messageId", ClawBotInboundMessage.MAX_MESSAGE_ID_LENGTH));
            payload.addProperty("text", requireText(text));
            return readAccepted(send("SESSION_REPLY", payload));
        }
    }

    /** Rejects an inbound message before provider execution and consumes it after replying. */
    public boolean rejectInbound(String sessionHandleId, String messageId, String text) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionHandleId", requireValue(sessionHandleId, "sessionHandleId"));
            payload.addProperty("messageId", requireBoundedValue(
                    messageId, "messageId", ClawBotInboundMessage.MAX_MESSAGE_ID_LENGTH));
            payload.addProperty("text", requireText(text));
            return readAccepted(send("SESSION_REJECT", payload));
        }
    }

    public ClawBotInboundMessage pollCommand(String sessionHandleId) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = ownerPayload(sessionHandleId);
            JsonObject result = send("SESSION_CONTROL_POLL", payload);
            if (!result.has("message") || result.get("message").isJsonNull()) {
                return null;
            }
            if (!result.get("message").isJsonObject()) {
                throw new IOException("CLAWBOT_IPC_RESULT_INVALID");
            }
            return ClawBotInboundMessage.fromJson(result.getAsJsonObject("message"));
        }
    }

    public boolean validateCommand(String sessionHandleId, String messageId) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = ownerPayload(sessionHandleId);
            payload.addProperty("messageId", requireBoundedValue(
                    messageId, "messageId", ClawBotInboundMessage.MAX_MESSAGE_ID_LENGTH));
            return readAccepted(send("SESSION_CONTROL_VALIDATE", payload));
        }
    }

    public boolean replyToCommand(String sessionHandleId, String messageId, String text) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = ownerPayload(sessionHandleId);
            payload.addProperty("messageId", requireBoundedValue(
                    messageId, "messageId", ClawBotInboundMessage.MAX_MESSAGE_ID_LENGTH));
            payload.addProperty("text", requireText(text));
            return readAccepted(send("SESSION_CONTROL_REPLY", payload));
        }
    }

    /** Rejects a control action before execution and consumes it after replying. */
    public boolean rejectCommand(String sessionHandleId, String messageId, String text) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = ownerPayload(sessionHandleId);
            payload.addProperty("messageId", requireBoundedValue(
                    messageId, "messageId", ClawBotInboundMessage.MAX_MESSAGE_ID_LENGTH));
            payload.addProperty("text", requireText(text));
            return readAccepted(send("SESSION_CONTROL_REJECT", payload));
        }
    }

    /** Publishes only routing metadata; question content remains in the IDE. */
    public boolean updateInteraction(String handle, String messageId, String token) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = ownerPayload(handle);
            payload.addProperty("messageId", requireBoundedValue(
                    messageId, "messageId", ClawBotInboundMessage.MAX_MESSAGE_ID_LENGTH));
            payload.addProperty("interactionToken", token == null ? ""
                    : requireBoundedValue(token, "interactionToken", 256));
            return readAccepted(send("SESSION_INTERACTION", payload));
        }
    }

    /** Publishes an IDE-origin question to peers that currently selected this exact session generation. */
    public boolean publishLocalInteraction(String handle, String generation, String token, String prompt) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = ownerPayload(handle);
            payload.addProperty("generation", requireBoundedValue(generation, "generation", 256));
            payload.addProperty("interactionToken", token == null ? "" : token);
            payload.addProperty("text", prompt == null ? "" : prompt);
            JsonObject result = send("SESSION_LOCAL_INTERACTION", payload);
            return readAccepted(result) && (!result.has("deliveryStatus") || !"PENDING".equals(result.get("deliveryStatus").getAsString()));
        }
    }

    public boolean sendProgress(
            String sessionHandleId, String messageId, String eventId, String text) throws IOException {
        return sendProgress(sessionHandleId, messageId, eventId, text, false);
    }

    public boolean sendProgress(
            String sessionHandleId, String messageId, String eventId, String text, boolean important) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = ownerPayload(sessionHandleId);
            payload.addProperty("messageId", requireBoundedValue(
                    messageId, "messageId", ClawBotInboundMessage.MAX_MESSAGE_ID_LENGTH));
            payload.addProperty("eventId", requireBoundedValue(eventId, "eventId", 256));
            payload.addProperty("text", requireText(text));
            payload.addProperty("important", important);
            JsonObject result = send("SESSION_PROGRESS", payload);
            // A concurrent attempt is still pending; retry the same event instead of advancing its cursor.
            return readAccepted(result) && (!result.has("deliveryStatus")
                    || !"PENDING".equals(result.get("deliveryStatus").getAsString()));
        }
    }

    private JsonObject ownerPayload(String sessionHandleId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionHandleId", requireValue(sessionHandleId, "sessionHandleId"));
        payload.addProperty("instanceId", instanceId);
        payload.addProperty("connectionEpoch", connectionEpoch);
        return payload;
    }

    /** Sends a reply on behalf of a session after the gateway validates its ownership. */
    public void sendText(
            String sessionHandleId, String toUserId, String contextToken, String text) throws IOException {
        synchronized (requestLock) {
            ensureOpen();
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionHandleId", requireValue(sessionHandleId, "sessionHandleId"));
            payload.addProperty("toUserId", requireValue(toUserId, "toUserId"));
            payload.addProperty("contextToken", requireBoundedValue(
                    contextToken, "contextToken", ClawBotInboundMessage.MAX_CONTEXT_TOKEN_LENGTH));
            payload.addProperty("text", requireText(text));
            send("SEND_TEXT", payload);
        }
    }

    /** Starts bounded heartbeat/reconnect work for all locally registered sessions. */
    public synchronized void startHeartbeat() {
        if (closed || heartbeatTask != null) {
            return;
        }
        try {
            heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(
                    this::heartbeatTick,
                    heartbeatIntervalMillis,
                    heartbeatIntervalMillis,
                    TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (!closed) { throw stopped; }
        }
    }

    /** Never block the IDE shutdown thread or replay cleanup against a replacement binding. */
    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) { return; }
        closed = true;
        var task = heartbeatTask;
        if (task != null) { task.cancel(false); }
        var active = activeTransport;
        if (active != null) { active.close(); }
        registrations.clear();
        heartbeatExecutor.shutdownNow();
        // Normal tab removal still unregisters explicitly. Abandoned ownership expires by heartbeat lease.
    }

    private void heartbeatTick() {
        synchronized (requestLock) {
            if (closed || registrations.isEmpty()) {
                return;
            }
            try {
                if (snapshotPending) {
                    if (!replaceSnapshotInternal(new ArrayList<>(registrations.values()))) {
                        return;
                    }
                    snapshotPending = false;
                }
                for (ClawBotSessionRegistration registration : registrations.values()) {
                    if (!heartbeatInternal(registration.sessionHandleId(), registration.status())) {
                        snapshotPending = true;
                        return;
                    }
                }
            } catch (IOException | RuntimeException error) {
                snapshotPending = true;
            }
        }
    }

    private boolean heartbeatInternal(String sessionHandleId, ClawBotSessionStatus status) throws IOException {
        String safeSessionHandleId = requireValue(sessionHandleId, "sessionHandleId");
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionHandleId", safeSessionHandleId);
        payload.addProperty("instanceId", instanceId);
        payload.addProperty("connectionEpoch", connectionEpoch);
        payload.addProperty("status", Objects.requireNonNull(status, "status").name());
        boolean accepted = readAccepted(send("SESSION_HEARTBEAT", payload));
        if (!accepted) {
            snapshotPending = true;
            ownerRecoveryPending = true;
        }
        return accepted;
    }

    private boolean replaceSnapshotInternal(List<ClawBotSessionRegistration> values) throws IOException {
        Objects.requireNonNull(values, "registrations");
        if (values.size() > 256 || values.stream().map(ClawBotSessionRegistration::sessionHandleId).distinct().count() != values.size()) {
            throw new IllegalArgumentException("Invalid registration snapshot");
        }
        for (ClawBotSessionRegistration registration : values) {
            requireOwned(registration);
        }
        JsonObject payload = new JsonObject();
        payload.add("sessions", ClawBotSessionWireCodec.registrationsToJson(values));
        boolean accepted = readAccepted(send("SESSION_SNAPSHOT", payload));
        if (accepted) {
            registrations.clear();
            for (ClawBotSessionRegistration registration : values) {
                registrations.put(registration.sessionHandleId(), registration);
            }
            startHeartbeat();
        } else {
            snapshotPending = true;
            ownerRecoveryPending = true;
        }
        return accepted;
    }

    private JsonObject send(String type, JsonObject payload) throws IOException {
        if (snapshotPending && !"SESSION_SNAPSHOT".equals(type) && !"SESSION_HEARTBEAT".equals(type)) {
            if (!replaceSnapshotInternal(new ArrayList<>(registrations.values()))) {
                throw new IOException("CLAWBOT_SESSION_SNAPSHOT_RECOVERY_FAILED");
            }
            snapshotPending = false;
            notifyOwnerRecovery();
        }
        try {
            ClawBotLocalIpcServer.Endpoint endpoint = endpointResolver.resolve();
            ClawBotLocalIpcClient client = new ClawBotLocalIpcClient(endpoint, instanceId, connectionEpoch);
            activeTransport = client;
            if (closed) {
                client.close();
                throw new IOException("CLAWBOT_IDE_CLIENT_CLOSED");
            }
            ClawBotIpcEnvelope response = client.request(new ClawBotIpcEnvelope(
                    ClawBotIpcEnvelope.PROTOCOL_VERSION,
                    type,
                    UUID.randomUUID().toString(),
                    instanceId,
                    connectionEpoch,
                    payload));
            if (closed) { throw new IOException("CLAWBOT_IDE_CLIENT_CLOSED"); }
            if ("ERROR".equals(response.type())) {
                String errorCode = readString(response.payload(), "code");
                if (!errorCode.matches("CLAWBOT_[A-Z_]{1,64}")) { throw new IOException("CLAWBOT_IPC_RESULT_INVALID"); }
                if (isSessionRecoveryError(errorCode)) {
                    snapshotPending = true;
                    ownerRecoveryPending = true;
                }
                throw new IOException(errorCode);
            }
            String expected = "LIST_SESSIONS".equals(type) ? "SESSION_LIST_RESULT" : type + "_RESULT";
            if (!expected.equals(response.type())) { throw new IOException("CLAWBOT_IPC_RESULT_INVALID"); }
            return response.payload();
        } catch (IOException error) {
            snapshotPending = true;
            throw error;
        } finally {
            var finished = activeTransport;
            activeTransport = null;
            if (finished != null) { finished.close(); }
        }
    }

    public static boolean isSessionRecoveryError(String errorCode) {
        return "CLAWBOT_IPC_OWNER_MISMATCH".equals(errorCode)
                || "CLAWBOT_SESSION_NOT_OWNER".equals(errorCode)
                || "CLAWBOT_SESSION_CONTROL_NOT_OWNER".equals(errorCode);
    }

    private void notifyOwnerRecovery() {
        if (closed || !ownerRecoveryPending) {
            return;
        }
        ownerRecoveryPending = false;
        try {
            sessionRecoveryListener.run();
        } catch (RuntimeException ignored) {
            // Recovery notification must not fail the recovered IPC request.
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("CLAWBOT_IDE_CLIENT_CLOSED");
        }
    }

    private void requireOwned(ClawBotSessionRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        if (!instanceId.equals(registration.instanceId()) || registration.connectionEpoch() != connectionEpoch) {
            throw new IllegalArgumentException("Registration owner does not match client");
        }
    }

    private static boolean readAccepted(JsonObject payload) throws IOException {
        try {
            var value = payload.get("accepted");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException();
            }
            return value.getAsBoolean();
        } catch (RuntimeException error) {
            throw new IOException("CLAWBOT_IPC_RESULT_INVALID", error);
        }
    }

    private static JsonArray requireArray(JsonObject payload, String name) throws IOException {
        if (payload == null || !payload.has(name) || !payload.get(name).isJsonArray()) {
            throw new IOException("CLAWBOT_IPC_RESULT_INVALID");
        }
        return payload.getAsJsonArray(name);
    }

    private static String readString(JsonObject object, String name) throws IOException {
        try {
            var field = object.get(name);
            if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) { throw new IllegalArgumentException(); }
            String value = field.getAsString();
            if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("blank");
            }
            return value;
        } catch (RuntimeException error) {
            throw new IOException("CLAWBOT_IPC_RESULT_INVALID", error);
        }
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static String requireText(String value) {
        if (value == null || value.isEmpty() || value.length() > ClawBotInboundMessage.MAX_TEXT_LENGTH
                || value.chars().anyMatch(character -> character < 0x20
                && character != '\t' && character != '\n' && character != '\r' || character == 0x7F)) {
            throw new IllegalArgumentException("Invalid text");
        }
        return value;
    }

    private static String requireBoundedValue(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @FunctionalInterface
    interface EndpointResolver {
        ClawBotLocalIpcServer.Endpoint resolve() throws IOException;
    }
}
