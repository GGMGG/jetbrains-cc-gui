package com.github.claudecodegui.clawbot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable lifecycle messages encrypted with a domain-separated key from the active binding. */
final class ClawBotPendingDeliveryStore {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_BYTES = 24 * 1024 * 1024;
    private static final byte[] DOMAIN = "CCGUI ClawBot pending delivery v1".getBytes(StandardCharsets.UTF_8);
    private final ClawBotRuntimeFiles files;
    private final String name;
    private byte[] loadedBinding;
    private final int maxTextLength;
    private final Map<String, Delivery> entries = new LinkedHashMap<>();
    private boolean loaded;

    ClawBotPendingDeliveryStore(Path directory) {
        this(directory, "pending-deliveries.enc");
    }

    ClawBotPendingDeliveryStore(Path directory, String name) {
        this(directory, name, ClawBotInboundMessage.MAX_TEXT_LENGTH);
    }

    ClawBotPendingDeliveryStore(Path directory, String name, int maxTextLength) {
        files = new ClawBotRuntimeFiles(directory);
        this.name = name;
        this.maxTextLength = maxTextLength;
    }

    synchronized void reload() {
        entries.clear();
        loaded = false;
    }

    synchronized Delivery put(Delivery delivery, String token) throws IOException {
        return putAll(List.of(delivery), token).get(0);
    }

    synchronized List<Delivery> putAll(List<Delivery> deliveries, String token) throws IOException {
        load(token);
        Map<String, Delivery> previous = new LinkedHashMap<>(entries);
        try {
            for (Delivery delivery : deliveries) {
                validate(delivery);
                entries.putIfAbsent(delivery.id(), delivery);
            }
            long active = entries.values().stream().filter(value -> !value.unknown()).count();
            long ordinary = entries.values().stream().filter(value -> !value.unknown() && !value.requireAuthorization()).count();
            if (active > MAX_ENTRIES || ordinary > MAX_ENTRIES - 32) {
                throw new IOException("CLAWBOT_DELIVERY_QUEUE_FULL");
            }
            trimArchived();
            save(token);
            return deliveries.stream().map(value -> entries.get(value.id())).toList();
        } catch (IOException error) {
            entries.clear();
            entries.putAll(previous);
            throw error;
        }
    }

    synchronized void removeRecipient(String recipient, String token) throws IOException {
        load(token);
        Map<String, Delivery> previous = new LinkedHashMap<>(entries);
        entries.values().removeIf(value -> value.recipient().equals(recipient));
        try {
            save(token);
        } catch (IOException error) {
            entries.clear();
            entries.putAll(previous);
            throw error;
        }
    }

    private void trimArchived() {
        long archived = entries.values().stream().filter(Delivery::unknown).count();
        var iterator = entries.values().iterator();
        while (archived > MAX_ENTRIES && iterator.hasNext()) {
            if (iterator.next().unknown()) {
                iterator.remove();
                archived--;
            }
        }
    }

    synchronized List<Delivery> pending(String token) throws IOException {
        load(token);
        return List.copyOf(entries.values());
    }

    synchronized void replace(Delivery delivery, String token) throws IOException {
        load(token);
        validate(delivery);
        Delivery previous = entries.get(delivery.id());
        if (previous == null) {
            throw new IOException("CLAWBOT_REPLY_BODY_UNAVAILABLE");
        }
        entries.put(delivery.id(), delivery);
        try {
            save(token);
        } catch (IOException error) {
            entries.put(delivery.id(), previous);
            throw error;
        }
    }

    synchronized void remove(String id, String token) throws IOException {
        load(token);
        Map<String, Delivery> snapshot = new LinkedHashMap<>(entries);
        Delivery previous = entries.remove(id);
        if (previous != null) {
            try {
                entries.replaceAll((key, value) -> id.equals(value.predecessor())
                        ? new Delivery(value.id(), value.recipient(), value.context(), value.text(), value.requireAuthorization(), null,
                                value.unknown(), value.groupId(), value.recoverySource()) : value);
                save(token);
            } catch (IOException error) {
                entries.clear();
                entries.putAll(snapshot);
                throw error;
            }
        }
    }

    synchronized void clear() throws IOException {
        files.prepare();
        files.remove(name);
        entries.clear();
        loaded = false;
    }

    synchronized void markUnknown(String id, String token) throws IOException {
        load(token);
        Delivery value = entries.get(id);
        if (value != null && !value.unknown()) {
            Map<String, Delivery> previous = new LinkedHashMap<>(entries);
            entries.replaceAll((key, entry) -> key.equals(id)
                    || (value.groupId() != null && value.groupId().equals(entry.groupId()))
                    ? new Delivery(entry.id(), entry.recipient(), entry.context(), entry.text(),
                            entry.requireAuthorization(), entry.predecessor(), true, entry.groupId(), entry.recoverySource()) : entry);
            try {
                trimArchived();
                save(token);
            } catch (IOException error) {
                entries.clear();
                entries.putAll(previous);
                throw error;
            }
        }
    }

    private void load(String token) throws IOException {
        byte[] binding = bindingFingerprint(token);
        if (loaded && java.security.MessageDigest.isEqual(binding, loadedBinding)) { return; }
        entries.clear();
        loaded = false;
        files.prepare();
        byte[] encrypted = files.read(name, MAX_BYTES);
        if (encrypted != null) {
            try {
                if (encrypted.length < 28) {
                    throw new IllegalArgumentException("Invalid encrypted payload");
                }
                Cipher cipher = cipher(Cipher.DECRYPT_MODE, token, Arrays.copyOf(encrypted, 12));
                byte[] plain = cipher.doFinal(encrypted, 12, encrypted.length - 12);
                JsonArray array = ClawBotIpcJson.parseOutbox(plain).getAsJsonArray();
                if (array.size() > MAX_ENTRIES * 2) {
                    throw new IllegalArgumentException("Invalid queue size");
                }
                Gson gson = new Gson();
                for (var element : array) {
                    Delivery value = gson.fromJson(element, Delivery.class);
                    if (value == null || value.id() == null || value.text() == null || value.recipient() == null
                            || value.context() == null || value.text().length() > maxTextLength) {
                        throw new IllegalArgumentException("Invalid delivery");
                    }
                    validate(value);
                    if (entries.put(value.id(), value) != null) { throw new IllegalArgumentException("Duplicate delivery"); }
                }
            } catch (GeneralSecurityException | RuntimeException error) {
                entries.clear();
                throw new IOException("CLAWBOT_DELIVERY_STORE_INVALID", error);
            }
        }
        loadedBinding = binding;
        loaded = true;
    }

    private void save(String token) throws IOException {
        files.prepare();
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            byte[] plain = new Gson().toJson(entries.values()).getBytes(StandardCharsets.UTF_8);
            if (plain.length > MAX_BYTES - 28) { throw new IOException("CLAWBOT_DELIVERY_QUEUE_FULL"); }
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, token, iv).doFinal(plain);
            byte[] envelope = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, envelope, iv.length, encrypted.length);
            files.write(name, envelope);
        } catch (GeneralSecurityException error) {
            throw new IOException("CLAWBOT_DELIVERY_STORE_UNAVAILABLE", error);
        }
    }

    private static byte[] bindingFingerprint(String token) throws IOException {
        if (token == null || token.isBlank() || token.length() > 8192 || token.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("CLAWBOT_DELIVERY_BINDING_INVALID");
        }
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (GeneralSecurityException unavailable) { throw new IOException("CLAWBOT_DELIVERY_BINDING_INVALID"); }
    }

    private void validate(Delivery value) throws IOException {
        if (value == null || !bounded(value.id(), 256, false) || !bounded(value.recipient(), 512, false)
                || !bounded(value.context(), 8192, true) || value.text() == null || value.text().length() > maxTextLength
                || (value.predecessor() != null && !bounded(value.predecessor(), 256, false))
                || (value.groupId() != null && !bounded(value.groupId(), 256, false))
                || (value.recoverySource() != null && !bounded(value.recoverySource(), 256, false))) {
            throw new IOException("CLAWBOT_DELIVERY_INVALID");
        }
    }

    private static boolean bounded(String value, int length, boolean empty) {
        return value != null && value.length() <= length && (empty || !value.isBlank())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static Cipher cipher(int mode, String token, byte[] iv) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(mac.doFinal(DOMAIN), "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(DOMAIN);
        return cipher;
    }

    record Delivery(String id, String recipient, String context, String text, boolean requireAuthorization, String predecessor,
                    boolean unknown, String groupId, String recoverySource) {
        Delivery(String id, String recipient, String context, String text, boolean requireAuthorization, String predecessor, boolean unknown, String groupId) {
            this(id, recipient, context, text, requireAuthorization, predecessor, unknown, groupId, null);
        }
        Delivery(String id, String recipient, String context, String text, boolean requireAuthorization, String predecessor, boolean unknown) {
            this(id, recipient, context, text, requireAuthorization, predecessor, unknown, null);
        }
        Delivery(String id, String recipient, String context, String text, boolean requireAuthorization, String predecessor) {
            this(id, recipient, context, text, requireAuthorization, predecessor, false);
        }
    }
}
