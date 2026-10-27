package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.fields;
import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.identifier;
import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.string;

/** An explicit native allowlist: scene metadata and activation of an existing exact tab only. */
final class PetOfficeLocalProtocol {
    static final int MAX_REQUEST_BYTES = 8 * 1024;

    record Request(String requestId, String action, String pageToken, PetOfficeTabNavigator.Request navigation) { }

    private PetOfficeLocalProtocol() { }

    static Request parse(String message, String expectedPage) throws IOException {
        if (message == null || message.length() > MAX_REQUEST_BYTES) {
            throw new IOException("OFFICE_REQUEST_INVALID");
        }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new IOException("OFFICE_REQUEST_INVALID");
        }
        JsonObject value = PetOfficeJsonCodec.parse(bytes);
        fields(value, "requestId", "action", "pageToken", "payload");
        String id = identifier(value, "requestId", false);
        String page = identifier(value, "pageToken", false);
        if (!page.equals(expectedPage) || !value.get("payload").isJsonObject()) {
            throw new IOException("OFFICE_PAGE_EXPIRED");
        }
        String action = string(value, "action", 32);
        JsonObject payload = value.getAsJsonObject("payload");
        PetOfficeTabNavigator.Request navigation = null;
        if ("scene".equals(action)) {
            fields(payload);
        } else if ("activate".equals(action)) {
            fields(payload, "sourceKey", "routeGeneration", "interactionId");
            String key = string(payload, "sourceKey", 386);
            try {
                PetOfficeSessionRegistry.parseKey(key);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("OFFICE_SOURCE_INVALID");
            }
            navigation = new PetOfficeTabNavigator.Request(id, key, identifier(payload, "routeGeneration", false), identifier(payload, "interactionId", true));
        } else {
            throw new IOException("OFFICE_ACTION_INVALID");
        }
        return new Request(id, action, page, navigation);
    }

    static JsonObject response(Request request, JsonObject data, String error) {
        JsonObject value = new JsonObject();
        value.addProperty("requestId", request.requestId());
        value.addProperty("action", request.action());
        value.addProperty("pageToken", request.pageToken());
        value.addProperty("success", error == null);
        if (error == null) {
            value.add("data", data);
        } else {
            value.addProperty("error", error);
        }
        return value;
    }
}
