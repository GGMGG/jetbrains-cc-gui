package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;

/** Ownership epochs and route revisions must survive JSON decoding without coercion or truncation. */
final class ClawBotJsonNumbers {
    private ClawBotJsonNumbers() { }

    static long nonNegativeLong(JsonObject object, String name) {
        try {
            return nonNegativeLong(object.get(name));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid numeric field " + name);
        }
    }

    static long nonNegativeLong(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid numeric value");
        }
        long result = value.getAsBigDecimal().longValueExact();
        if (result < 0) { throw new IllegalArgumentException("Invalid numeric value"); }
        return result;
    }
}
