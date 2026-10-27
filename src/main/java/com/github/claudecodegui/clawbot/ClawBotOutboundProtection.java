package com.github.claudecodegui.clawbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Leader-owned, persisted protective limits; these are local policy, not published iLink quotas. */
final class ClawBotOutboundProtection {
    static final long WINDOW_MILLIS = 300_000L;
    static final int WINDOW_LIMIT = 6;
    static final int ORDINARY_LIMIT = 4;
    private final ClawBotRuntimeFiles files;
    private final Deque<Long> attempts = new ArrayDeque<>();
    private final Map<String, Integer> ordinaryCounts = new LinkedHashMap<>();
    private final Map<String, String> lastInbound = new LinkedHashMap<>();
    private long cooldownUntil;
    private boolean loaded;

    ClawBotOutboundProtection(Path directory) {
        files = new ClawBotRuntimeFiles(directory);
    }

    synchronized void reload() {
        loaded = false;
        attempts.clear();
        ordinaryCounts.clear();
        lastInbound.clear();
        cooldownUntil = 0;
    }

    synchronized boolean acquire(long now, boolean ordinary) throws IOException {
        return acquire(now, ordinary, "");
    }

    synchronized boolean acquire(long now, boolean ordinary, String conversation) throws IOException {
        load();
        prune(now);
        if (now < cooldownUntil || attempts.size() >= (ordinary ? ORDINARY_LIMIT : WINDOW_LIMIT)
                || (ordinary && !conversation.isEmpty() && ordinaryCounts.getOrDefault(conversation, 0) >= 6)) {
            return false;
        }
        attempts.addLast(now);
        if (ordinary && !conversation.isEmpty()) {
            ordinaryCounts.merge(conversation, 1, Integer::sum);
            while (ordinaryCounts.size() > 256) {
                ordinaryCounts.remove(ordinaryCounts.keySet().iterator().next());
            }
        }
        save();
        return true;
    }

    synchronized void coolDown(long now) throws IOException {
        coolDown(now, WINDOW_MILLIS);
    }

    synchronized void coolDown(long now, long delay) throws IOException {
        load();
        cooldownUntil = Math.max(cooldownUntil, now + Math.max(WINDOW_MILLIS, Math.min(delay, 86_400_000L)));
        save();
    }

    synchronized void renewConversation(String conversation, String inboundId) throws IOException {
        load();
        if (!inboundId.equals(lastInbound.get(conversation))) {
            lastInbound.put(conversation, inboundId);
            ordinaryCounts.remove(conversation);
            while (lastInbound.size() > 256) {
                lastInbound.remove(lastInbound.keySet().iterator().next());
            }
            save();
        }
    }

    synchronized long nextAllowedAt(long now) throws IOException {
        load();
        prune(now);
        return Math.max(cooldownUntil, attempts.size() >= WINDOW_LIMIT ? attempts.getFirst() + WINDOW_MILLIS : now);
    }

    private void prune(long now) {
        while (!attempts.isEmpty() && attempts.getFirst() <= now - WINDOW_MILLIS) {
            attempts.removeFirst();
        }
    }

    private void load() throws IOException {
        if (loaded) {
            return;
        }
        files.prepare();
        byte[] bytes = files.read("outbound-protection.json", 65_536);
        if (bytes != null) {
            try {
                JsonObject data = ClawBotIpcJson.parseJournal(bytes).getAsJsonObject();
                JsonArray values = data.getAsJsonArray("attempts");
                if (values.size() > WINDOW_LIMIT) {
                    throw new IllegalArgumentException("Invalid attempt count");
                }
                long previous = 0;
                for (var value : values) {
                    long timestamp = ClawBotJsonNumbers.nonNegativeLong(value);
                    if (timestamp < previous) {
                        throw new IllegalArgumentException("Invalid timestamp");
                    }
                    attempts.add(timestamp);
                    previous = timestamp;
                }
                cooldownUntil = ClawBotJsonNumbers.nonNegativeLong(data.get("cooldownUntil"));
                if (data.has("ordinaryCounts")) {
                    JsonObject counts = data.getAsJsonObject("ordinaryCounts");
                    if (counts.size() > 256) {
                        throw new IllegalArgumentException("Invalid budget count");
                    }
                    for (var entry : counts.entrySet()) {
                        int value = Math.toIntExact(ClawBotJsonNumbers.nonNegativeLong(entry.getValue()));
                        if (!entry.getKey().matches("[a-f0-9-]{36}") || value < 0 || value > 6) {
                            throw new IllegalArgumentException("Invalid budget");
                        }
                        ordinaryCounts.put(entry.getKey(), value);
                    }
                }
                if (data.has("lastInbound")) {
                    JsonObject inbound = data.getAsJsonObject("lastInbound");
                    if (inbound.size() > 256) {
                        throw new IllegalArgumentException("Invalid inbound count");
                    }
                    for (var entry : inbound.entrySet()) {
                        if (!entry.getKey().matches("[a-f0-9-]{36}") || !entry.getValue().getAsString().matches("[a-f0-9-]{36}")) {
                            throw new IllegalArgumentException("Invalid inbound identity");
                        }
                        lastInbound.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } catch (RuntimeException error) {
                attempts.clear();
                ordinaryCounts.clear();
                lastInbound.clear();
                throw new IOException("CLAWBOT_SEND_PROTECTION_UNAVAILABLE", error);
            }
        }
        loaded = true;
    }

    private void save() throws IOException {
        JsonObject data = new JsonObject();
        JsonArray values = new JsonArray();
        attempts.forEach(values::add);
        data.add("attempts", values);
        data.addProperty("cooldownUntil", cooldownUntil);
        JsonObject counts = new JsonObject();
        ordinaryCounts.forEach(counts::addProperty);
        data.add("ordinaryCounts", counts);
        JsonObject inbound = new JsonObject();
        lastInbound.forEach(inbound::addProperty);
        data.add("lastInbound", inbound);
        files.prepare();
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_536) { throw new IOException("CLAWBOT_SEND_PROTECTION_UNAVAILABLE"); }
        files.write("outbound-protection.json", bytes);
    }
}
