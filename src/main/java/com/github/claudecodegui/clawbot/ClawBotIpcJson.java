package com.github.claudecodegui.clawbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** Strict UTF-8 and bounded nesting before constructing the business envelope. */
final class ClawBotIpcJson {
    private ClawBotIpcJson() { }

    static JsonObject parse(byte[] bytes) throws IOException {
        JsonElement result = parseValue(bytes, 64, 256, 512);
        if (!result.isJsonObject()) {
            throw new IOException("CLAWBOT_IPC_JSON_INVALID");
        }
        return result.getAsJsonObject();
    }

    /** PasswordSafe payloads have their own bounded collection budget; wire limits stay unchanged. */
    static JsonElement parseStored(String json) throws IOException {
        if (json.length() > 4 * 1024 * 1024) {
            throw new IOException("CLAWBOT_STORE_JSON_INVALID");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4 * 1024 * 1024) {
            throw new IOException("CLAWBOT_STORE_JSON_INVALID");
        }
        return parseValue(bytes, 256, 512, 512);
    }

    static JsonElement parseJournal(byte[] bytes) throws IOException {
        if (bytes.length > 2 * 1024 * 1024) { throw new IOException("CLAWBOT_JOURNAL_JSON_INVALID"); }
        return parseValue(bytes, 256, 512, 2048);
    }

    private static JsonElement parseValue(byte[] bytes, int fieldLimit, int nameLimit, int arrayLimit) throws IOException {
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var reader = new JsonReader(new StringReader(json))) {
                reader.setLenient(false);
                JsonElement result = value(reader, 0, fieldLimit, nameLimit, arrayLimit);
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IOException("CLAWBOT_IPC_JSON_INVALID");
                }
                return result;
            }
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("CLAWBOT_IPC_JSON_INVALID");
        }
    }

    private static JsonElement value(JsonReader reader, int depth, int fieldLimit, int nameLimit, int arrayLimit) throws IOException {
        if (depth > 24) {
            throw new IOException("CLAWBOT_IPC_JSON_INVALID");
        }
        switch (reader.peek()) {
        case BEGIN_OBJECT:
            reader.beginObject();
            var object = new JsonObject();
            var names = new HashSet<String>();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.length() > nameLimit || names.size() >= fieldLimit || !names.add(name)) {
                    throw new IOException("CLAWBOT_IPC_JSON_INVALID");
                }
                object.add(name, value(reader, depth + 1, fieldLimit, nameLimit, arrayLimit));
            }
            reader.endObject();
            return object;
        case BEGIN_ARRAY:
            reader.beginArray();
            var array = new JsonArray();
            while (reader.hasNext()) {
                if (array.size() >= arrayLimit) {
                    throw new IOException("CLAWBOT_IPC_JSON_INVALID");
                }
                array.add(value(reader, depth + 1, fieldLimit, nameLimit, arrayLimit));
            }
            reader.endArray();
            return array;
        case STRING:
            return new JsonPrimitive(reader.nextString());
        case NUMBER:
            return new JsonPrimitive(new BigDecimal(reader.nextString()));
        case BOOLEAN:
            return new JsonPrimitive(reader.nextBoolean());
        case NULL:
            reader.nextNull();
            return JsonNull.INSTANCE;
        default:
            throw new IOException("CLAWBOT_IPC_JSON_INVALID");
        }
    }
}
