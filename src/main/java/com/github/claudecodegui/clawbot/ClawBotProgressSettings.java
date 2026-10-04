package com.github.claudecodegui.clawbot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Immutable intervals used when publishing progress messages for an active Claw Bot turn. */
public record ClawBotProgressSettings(
        int textIntervalMinutes,
        int idleReminderMinutes,
        int waitReminderMinutes) {

    static final int DEFAULT_TEXT_INTERVAL_MINUTES = 1;
    static final int DEFAULT_IDLE_REMINDER_MINUTES = 5;
    static final int DEFAULT_WAIT_REMINDER_MINUTES = 10;
    static final int MIN_INTERVAL_MINUTES = 1;
    static final int MAX_INTERVAL_MINUTES = 24 * 60;

    public ClawBotProgressSettings {
        textIntervalMinutes = validateMinutes(textIntervalMinutes, "textIntervalMinutes");
        idleReminderMinutes = validateMinutes(idleReminderMinutes, "idleReminderMinutes");
        waitReminderMinutes = validateMinutes(waitReminderMinutes, "waitReminderMinutes");
    }

    public static ClawBotProgressSettings defaults() {
        return new ClawBotProgressSettings(
                DEFAULT_TEXT_INTERVAL_MINUTES,
                DEFAULT_IDLE_REMINDER_MINUTES,
                DEFAULT_WAIT_REMINDER_MINUTES);
    }

    static ClawBotProgressSettings fromJson(JsonObject object) throws IOException {
        if (object == null) {
            throw new IOException("CLAWBOT_PROGRESS_SETTINGS_INVALID");
        }
        return new ClawBotProgressSettings(
                readMinutes(object, "textIntervalMinutes", DEFAULT_TEXT_INTERVAL_MINUTES),
                readMinutes(object, "idleReminderMinutes", DEFAULT_IDLE_REMINDER_MINUTES),
                readMinutes(object, "waitReminderMinutes", DEFAULT_WAIT_REMINDER_MINUTES));
    }

    static ClawBotProgressSettings fromUpdatePayload(JsonObject object) throws IOException {
        if (object == null || !object.has("textIntervalMinutes")
                || !object.has("idleReminderMinutes") || !object.has("waitReminderMinutes")) {
            throw new IOException("CLAWBOT_PROGRESS_SETTINGS_INVALID");
        }
        return new ClawBotProgressSettings(
                readRequiredMinutes(object, "textIntervalMinutes"),
                readRequiredMinutes(object, "idleReminderMinutes"),
                readRequiredMinutes(object, "waitReminderMinutes"));
    }

    JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("textIntervalMinutes", textIntervalMinutes);
        object.addProperty("idleReminderMinutes", idleReminderMinutes);
        object.addProperty("waitReminderMinutes", waitReminderMinutes);
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

    private static int readMinutes(JsonObject object, String name, int fallback) throws IOException {
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
            return validateMinutes(minutes, name);
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
        if (minutes < MIN_INTERVAL_MINUTES || minutes > MAX_INTERVAL_MINUTES) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return minutes;
    }
}
