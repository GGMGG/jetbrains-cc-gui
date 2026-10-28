package com.github.claudecodegui.clawbot;

import com.github.claudecodegui.bridge.BridgeDirectoryResolver;
import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Owned Node protocol process; no prompt, token or stderr is written to diagnostics. */
final class ClawBotIlinkProcess implements AutoCloseable {
    private static final int MAX_LINE_LENGTH = 256 * 1024;
    @FunctionalInterface interface ProcessStarter { Process start() throws IOException; }
    private record Pending(Process owner, CompletableFuture<JsonObject> result) { }
    private final ProcessStarter starter;
    private final long startTimeoutMillis;
    private final long requestTimeoutMillis;
    private final Object lifecycle = new Object();
    private final Object writerLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final Semaphore capacity = new Semaphore(64);
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> daemon(task, "clawbot-ilink-deadline"));
    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile CompletableFuture<Void> ready;
    private volatile boolean closed;

    ClawBotIlinkProcess() {
        this(new BridgeDirectoryResolver(), NodeDetector.getInstance(), new EnvironmentConfigurator());
    }

    ClawBotIlinkProcess(BridgeDirectoryResolver resolver, NodeDetector detector, EnvironmentConfigurator environment) {
        this(() -> {
            File directory = resolver.findSdkDir();
            if (directory == null) { throw new IOException("CLAWBOT_ILINK_BRIDGE_UNAVAILABLE"); }
            File script = new File(directory, "weixin-daemon.js");
            if (!script.isFile()) { throw new IOException("CLAWBOT_ILINK_DAEMON_UNAVAILABLE"); }
            String node = detector.findNodeExecutable();
            ProcessBuilder builder = new ProcessBuilder(NodeDetector.buildNodeScriptCommand(node, script.getAbsolutePath()));
            builder.directory(directory);
            environment.updateProcessEnvironment(builder, node);
            return builder.start();
        }, 10_000, 45_000);
    }

    ClawBotIlinkProcess(ProcessStarter starter, long startTimeoutMillis, long requestTimeoutMillis) {
        this.starter = java.util.Objects.requireNonNull(starter);
        if (startTimeoutMillis < 1 || requestTimeoutMillis < 1) { throw new IllegalArgumentException("Invalid protocol deadline"); }
        this.startTimeoutMillis = startTimeoutMillis;
        this.requestTimeoutMillis = requestTimeoutMillis;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    synchronized void start() throws IOException {
        if (closed) { throw new IOException("CLAWBOT_ILINK_PROCESS_CLOSED"); }
        if (isRunning()) { return; }
        Process started = starter.start();
        CompletableFuture<Void> startedReady = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (closed) {
                stop(started);
                throw new IOException("CLAWBOT_ILINK_PROCESS_CLOSED");
            }
            process = started;
            writer = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
            ready = startedReady;
        }
        daemon(() -> readResponses(started, startedReady), "clawbot-ilink-reader").start();
        daemon(() -> {
            try (var errors = started.getErrorStream()) {
                byte[] discard = new byte[8192];
                while (errors.read(discard) >= 0) { /* Drain without retaining or exposing diagnostic content. */ }
            } catch (IOException ignored) { /* Process exit closes the owned pipe. */ }
        }, "clawbot-ilink-stderr").start();
        try {
            startedReady.get(startTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stop(started);
            throw new IOException("CLAWBOT_ILINK_START_INTERRUPTED");
        } catch (Exception unavailable) {
            stop(started);
            throw new IOException(unavailable instanceof TimeoutException ? "CLAWBOT_ILINK_START_TIMEOUT" : "CLAWBOT_ILINK_START_FAILED");
        }
    }

    boolean isRunning() {
        synchronized (lifecycle) {
            var current = process;
            var acknowledged = ready;
            return !closed && current != null && current.isAlive() && acknowledged != null && acknowledged.isDone() && !acknowledged.isCompletedExceptionally();
        }
    }

    JsonObject request(String method, JsonObject params) throws IOException {
        if (method == null || !method.matches("[a-z_]{1,64}")) { throw new IOException("CLAWBOT_ILINK_METHOD_INVALID"); }
        Process current;
        BufferedWriter output;
        synchronized (lifecycle) {
            if (!isRunning()) { throw new IOException("CLAWBOT_ILINK_PROCESS_UNAVAILABLE"); }
            current = process;
            output = writer;
        }
        if (!capacity.tryAcquire()) { throw new IOException("CLAWBOT_ILINK_REQUEST_CAPACITY"); }
        String id = Long.toString(sequence.incrementAndGet());
        Pending request = new Pending(current, new CompletableFuture<>());
        pending.put(id, request);
        java.util.concurrent.ScheduledFuture<?> expiry = null;
        try {
            long timeout = requestTimeoutMillis + (method.equals("get_updates") ? 5000 : 0);
            expiry = deadlines.schedule(() -> {
                if (request.result().completeExceptionally(new IOException("CLAWBOT_ILINK_REQUEST_TIMEOUT"))) { stop(current); }
            }, timeout, TimeUnit.MILLISECONDS);
            JsonObject packet = new JsonObject();
            packet.addProperty("id", id);
            packet.addProperty("method", method);
            packet.add("params", params == null ? new JsonObject() : params.deepCopy());
            String encoded = packet.toString();
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_LENGTH) { throw new IOException("CLAWBOT_ILINK_REQUEST_INVALID"); }
            synchronized (writerLock) {
                if (closed || process != current || output == null || request.result().isCompletedExceptionally()) {
                    throw new IOException("CLAWBOT_ILINK_PROCESS_UNAVAILABLE");
                }
                output.write(encoded);
                output.newLine();
                output.flush();
            }
            return request.result().get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("CLAWBOT_ILINK_REQUEST_INTERRUPTED");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof IlinkDaemonException reported) { throw new IOException(reported.code(), reported); }
            if (failed.getCause() instanceof IOException checked) { throw checked; }
            throw new IOException("CLAWBOT_ILINK_REQUEST_FAILED");
        } catch (TimeoutException timeout) {
            stop(current);
            throw new IOException("CLAWBOT_ILINK_REQUEST_TIMEOUT");
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            throw new IOException("CLAWBOT_ILINK_PROCESS_CLOSED");
        } finally {
            request.result().completeExceptionally(new IOException("CLAWBOT_ILINK_REQUEST_FINISHED"));
            if (expiry != null) { expiry.cancel(false); }
            pending.remove(id, request);
            capacity.release();
        }
    }

    private void readResponses(Process started, CompletableFuture<Void> startedReady) {
        try (var input = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = boundedLine(input)) != null) {
                if (!line.isEmpty()) { dispatchLine(line, started, startedReady); }
            }
        } catch (IOException | RuntimeException invalid) {
            // Protocol failures disclose no provider data; callers receive only the stable process code.
        } finally {
            stop(started);
            startedReady.completeExceptionally(new IOException("CLAWBOT_ILINK_PROCESS_EXITED"));
            pending.forEach((id, value) -> {
                if (value.owner() == started) { value.result().completeExceptionally(new IOException("CLAWBOT_ILINK_PROCESS_EXITED")); }
            });
        }
    }

    private void dispatchLine(String line, Process started, CompletableFuture<Void> startedReady) throws IOException {
        JsonObject packet = ClawBotIpcJson.parse(line.getBytes(StandardCharsets.UTF_8));
        if ("daemon".equals(text(packet, "type")) && "ready".equals(text(packet, "event"))) {
            if (process == started && !closed) { startedReady.complete(null); }
            return;
        }
        String id = text(packet, "id");
        Pending response = id == null ? null : pending.get(id);
        if (response == null || response.owner() != started) { return; }
        if (packet.has("error") && packet.get("error").isJsonObject()) {
            var error = packet.getAsJsonObject("error");
            String code = text(error, "code");
            if (code == null || !code.matches("(?:ILINK|CLAWBOT)_[A-Z0-9_]{1,80}")) { code = "CLAWBOT_ILINK_REQUEST_FAILED"; }
            String detail = text(error, "detail");
            String numeric = "(?:ret|errcode|http|retryAfterMs)=[0-9.eE+\\-]{1,32}";
            if (detail != null && (detail.length() > 512 || !detail.matches(numeric + "(?:;" + numeric + "){0,3}"))) {
                detail = null;
            }
            response.result().completeExceptionally(new IlinkDaemonException(code, detail));
        } else if (!packet.has("result") || !packet.get("result").isJsonObject()) {
            response.result().completeExceptionally(new IOException("CLAWBOT_ILINK_RESPONSE_INVALID"));
        } else {
            response.result().complete(packet.getAsJsonObject("result"));
        }
    }

    private static String boundedLine(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') { line.setLength(line.length() - 1); }
                return line.toString();
            }
            line.append((char) value);
            if (line.length() > MAX_LINE_LENGTH) { throw new IOException("CLAWBOT_ILINK_RESPONSE_INVALID"); }
        }
        if (!line.isEmpty()) { throw new IOException("CLAWBOT_ILINK_RESPONSE_INVALID"); }
        return null;
    }

    private static String text(JsonObject packet, String name) {
        var value = packet.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    @Override public void close() {
        Process current;
        synchronized (lifecycle) {
            if (closed) { return; }
            closed = true;
            current = process;
            process = null;
            writer = null;
            if (ready != null) { ready.completeExceptionally(new IOException("CLAWBOT_ILINK_PROCESS_CLOSED")); }
        }
        pending.forEach((id, value) -> value.result().completeExceptionally(new IOException("CLAWBOT_ILINK_PROCESS_CLOSED")));
        pending.clear();
        deadlines.shutdownNow();
        stop(current);
    }

    private void stop(Process target) {
        if (target == null) { return; }
        synchronized (lifecycle) {
            if (process == target) {
                process = null;
                writer = null;
                if (ready != null) { ready.completeExceptionally(new IOException("CLAWBOT_ILINK_PROCESS_STOPPED")); }
            }
        }
        target.destroyForcibly();
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    static final class IlinkDaemonException extends Exception {
        private final String code;
        private final String detail;
        private IlinkDaemonException(String code, String detail) {
            super(code);
            this.code = code;
            this.detail = detail;
        }
        private String code() { return code; }
        String detail() { return detail; }
    }
}
