package com.github.claudecodegui.clawbot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Immutable intervals used when publishing progress messages for an active Claw Bot turn. */
public record ClawBotProgressSettings(
        int textIntervalMinutes,
        int idleReminderMinutes,
        int waitReminderMinutes,
        int initialCheckDelaySeconds,
        int maxNotifications,
        int excerptMaxCharacters,
        int sessionIdleTimeoutMinutes) {

    static final int DEFAULT_TEXT_INTERVAL_MINUTES = 1;
    static final int DEFAULT_IDLE_REMINDER_MINUTES = 5;
    static final int DEFAULT_WAIT_REMINDER_MINUTES = 10;
    static final int DEFAULT_INITIAL_CHECK_DELAY_SECONDS = 15;
    static final int DEFAULT_MAX_NOTIFICATIONS = 12;
    static final int DEFAULT_EXCERPT_MAX_CHARACTERS = 800;
    static final int DEFAULT_SESSION_IDLE_TIMEOUT_MINUTES = 30;
    static final int MIN_INTERVAL_MINUTES = 1;
    static final int MAX_INTERVAL_MINUTES = 24 * 60;
    static final int MIN_INITIAL_CHECK_DELAY_SECONDS = 1;
    static final int MAX_INITIAL_CHECK_DELAY_SECONDS = 5 * 60;
    static final int MIN_MAX_NOTIFICATIONS = 1;
    static final int MAX_MAX_NOTIFICATIONS = 100;
    static final int MIN_EXCERPT_MAX_CHARACTERS = 100;
    static final int MAX_EXCERPT_MAX_CHARACTERS = 4_000;

    public ClawBotProgressSettings(int textIntervalMinutes, int idleReminderMinutes, int waitReminderMinutes) {
        this(textIntervalMinutes, idleReminderMinutes, waitReminderMinutes,
                DEFAULT_INITIAL_CHECK_DELAY_SECONDS, DEFAULT_MAX_NOTIFICATIONS,
                DEFAULT_EXCERPT_MAX_CHARACTERS, DEFAULT_SESSION_IDLE_TIMEOUT_MINUTES);
    }

    public ClawBotProgressSettings {
        textIntervalMinutes = validateMinutes(textIntervalMinutes, "textIntervalMinutes");
        idleReminderMinutes = validateMinutes(idleReminderMinutes, "idleReminderMinutes");
        waitReminderMinutes = validateMinutes(waitReminderMinutes, "waitReminderMinutes");
        initialCheckDelaySeconds = validateRange(initialCheckDelaySeconds,
                MIN_INITIAL_CHECK_DELAY_SECONDS, MAX_INITIAL_CHECK_DELAY_SECONDS, "initialCheckDelaySeconds");
        maxNotifications = validateRange(maxNotifications,
                MIN_MAX_NOTIFICATIONS, MAX_MAX_NOTIFICATIONS, "maxNotifications");
        excerptMaxCharacters = validateRange(excerptMaxCharacters,
                MIN_EXCERPT_MAX_CHARACTERS, MAX_EXCERPT_MAX_CHARACTERS, "excerptMaxCharacters");
        sessionIdleTimeoutMinutes = validateMinutes(sessionIdleTimeoutMinutes, "sessionIdleTimeoutMinutes");
    }

    public static ClawBotProgressSettings defaults() {
        return new ClawBotProgressSettings(
                DEFAULT_TEXT_INTERVAL_MINUTES,
                DEFAULT_IDLE_REMINDER_MINUTES,
                DEFAULT_WAIT_REMINDER_MINUTES,
                DEFAULT_INITIAL_CHECK_DELAY_SECONDS,
                DEFAULT_MAX_NOTIFICATIONS,
                DEFAULT_EXCERPT_MAX_CHARACTERS,
                DEFAULT_SESSION_IDLE_TIMEOUT_MINUTES);
    }

    static ClawBotProgressSettings fromJson(JsonObject object) throws IOException {
        if (object == null) {
            throw new IOException("CLAWBOT_PROGRESS_SETTINGS_INVALID");
        }
        return new ClawBotProgressSettings(
                readMinutes(object, "textIntervalMinutes", DEFAULT_TEXT_INTERVAL_MINUTES),
                readMinutes(object, "idleReminderMinutes", DEFAULT_IDLE_REMINDER_MINUTES),
                readMinutes(object, "waitReminderMinutes", DEFAULT_WAIT_REMINDER_MINUTES),
                readInteger(object, "initialCheckDelaySeconds", DEFAULT_INITIAL_CHECK_DELAY_SECONDS,
                        MIN_INITIAL_CHECK_DELAY_SECONDS, MAX_INITIAL_CHECK_DELAY_SECONDS),
                readInteger(object, "maxNotifications", DEFAULT_MAX_NOTIFICATIONS,
                        MIN_MAX_NOTIFICATIONS, MAX_MAX_NOTIFICATIONS),
                readInteger(object, "excerptMaxCharacters", DEFAULT_EXCERPT_MAX_CHARACTERS,
                        MIN_EXCERPT_MAX_CHARACTERS, MAX_EXCERPT_MAX_CHARACTERS),
                readMinutes(object, "sessionIdleTimeoutMinutes", DEFAULT_SESSION_IDLE_TIMEOUT_MINUTES));
    }

    static ClawBotProgressSettings fromUpdatePayload(JsonObject object) throws IOException {
        return fromUpdatePayload(object, defaults());
    }

    static ClawBotProgressSettings fromUpdatePayload(
            JsonObject object, ClawBotProgressSettings fallback) throws IOException {
        if (object == null || !object.has("textIntervalMinutes")
                || !object.has("idleReminderMinutes") || !object.has("waitReminderMinutes")) {
            throw new IOException("CLAWBOT_PROGRESS_SETTINGS_INVALID");
        }
        ClawBotProgressSettings safeFallback = fallback == null ? defaults() : fallback;
        return new ClawBotProgressSettings(
                readRequiredMinutes(object, "textIntervalMinutes"),
                readRequiredMinutes(object, "idleReminderMinutes"),
                readRequiredMinutes(object, "waitReminderMinutes"),
                readInteger(object, "initialCheckDelaySeconds", safeFallback.initialCheckDelaySeconds(),
                        MIN_INITIAL_CHECK_DELAY_SECONDS, MAX_INITIAL_CHECK_DELAY_SECONDS),
                readInteger(object, "maxNotifications", safeFallback.maxNotifications(),
                        MIN_MAX_NOTIFICATIONS, MAX_MAX_NOTIFICATIONS),
                readInteger(object, "excerptMaxCharacters", safeFallback.excerptMaxCharacters(),
                        MIN_EXCERPT_MAX_CHARACTERS, MAX_EXCERPT_MAX_CHARACTERS),
                readMinutes(object, "sessionIdleTimeoutMinutes", safeFallback.sessionIdleTimeoutMinutes()));
    }

    JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("textIntervalMinutes", textIntervalMinutes);
        object.addProperty("idleReminderMinutes", idleReminderMinutes);
        object.addProperty("waitReminderMinutes", waitReminderMinutes);
        object.addProperty("initialCheckDelaySeconds", initialCheckDelaySeconds);
        object.addProperty("maxNotifications", maxNotifications);
        object.addProperty("excerptMaxCharacters", excerptMaxCharacters);
        object.addProperty("sessionIdleTimeoutMinutes", sessionIdleTimeoutMinutes);
        return object;
    }

    long textIntervalNanos() {
        return TimeUnit.MINUTES.toNanos(textIntervalMinutes);
    }

    long idleReminderNanos() {
        return TimeUnit.MINUTES.toNanos(idleReminderMinutes);
    }

    long waitReminderNanos() {
        return TimeUnit.MINUTES.toNanos(waitReminderMinutes);
    }

    long initialCheckDelayNanos() {
        return TimeUnit.SECONDS.toNanos(initialCheckDelaySeconds);
    }

    long sessionIdleTimeoutMillis() {
        return TimeUnit.MINUTES.toMillis(sessionIdleTimeoutMinutes);
    }

    private static int readMinutes(JsonObject object, String name, int fallback) throws IOException {
        return readInteger(object, name, fallback, MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES);
    }

    private static int readInteger(JsonObject object, String name, int fallback, int minimum, int maximum)
            throws IOException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("CLAWBOT_PROGRESS_SETTINGS_INVALID");
        }
        try {
            int minutes = value.getAsInt();
            if (value.getAsDouble() != minutes) {
                throw new NumberFormatException("not an integer");
            }
            return validateRange(minutes, minimum, maximum, name);
        } catch (RuntimeException error) {
            throw new IOException("CLAWBOT_PROGRESS_SETTINGS_INVALID", error);
        }
    }

    private static int readRequiredMinutes(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            throw new IOException("CLAWBOT_PROGRESS_SETTINGS_INVALID");
        }
        return readMinutes(object, name, MIN_INTERVAL_MINUTES);
    }

    private static int validateMinutes(int minutes, String name) {
        return validateRange(minutes, MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES, name);
    }

    private static int validateRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }
}
