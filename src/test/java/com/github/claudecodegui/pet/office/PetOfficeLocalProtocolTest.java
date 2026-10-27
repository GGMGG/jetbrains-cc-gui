package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class PetOfficeLocalProtocolTest {
    private static final String PAGE = "page-1";

    @Test
    public void metadataReadHasNoCallerSelectedTargetOrPath() throws Exception {
        var request = parse("scene", new JsonObject());
        assertEquals("scene", request.action());
        assertNull(request.navigation());
        JsonObject payload = new JsonObject();
        payload.addProperty("path", "private");
        assertThrows(IOException.class, () -> parse("scene", payload));
        assertThrows(IOException.class, () -> parse("send", new JsonObject()));
    }

    @Test
    public void navigationPreservesOpaqueBindingRouteAndInteraction() throws Exception {
        JsonObject payload = target();
        var request = parse("activate", payload);
        assertEquals("instance/tab/binding", request.navigation().sourceKey());
        assertEquals("route-1", request.navigation().routeGeneration());
        assertEquals("question-1", request.navigation().interactionId());
        payload.addProperty("interactionId", "");
        assertEquals("", parse("activate", payload).navigation().interactionId());
        payload.addProperty("sourceKey", "instance/tab/../binding");
        assertThrows(IOException.class, () -> parse("activate", payload));
    }

    @Test
    public void aRetiredPageCannotNavigateAndDuplicateFieldsCannotOverrideAuthority() {
        String request = envelope("activate", target());
        assertThrows(IOException.class, () -> PetOfficeLocalProtocol.parse(request, "page-2"));
        String duplicate = request.replace("\"pageToken\":\"page-1\"", "\"pageToken\":\"page-2\",\"pageToken\":\"page-1\"");
        assertThrows(IOException.class, () -> PetOfficeLocalProtocol.parse(duplicate, PAGE));
    }

    @Test
    public void rejectsOversizedMultibyteRequestsAndInvalidPayloadTypes() {
        JsonObject payload = target();
        payload.addProperty("extra", "界".repeat(3_000));
        assertThrows(IOException.class, () -> parse("activate", payload));
        assertThrows(IOException.class, () -> PetOfficeLocalProtocol.parse(envelope("scene", new JsonObject()).replace("\"payload\":{}", "\"payload\":[]"), PAGE));
    }

    @Test
    public void repliesRemainScopedToTheOriginatingActionAndPage() throws Exception {
        var request = parse("scene", new JsonObject());
        JsonObject response = PetOfficeLocalProtocol.response(request, null, "OFFICE_BUSY");
        assertEquals(PAGE, response.get("pageToken").getAsString());
        assertEquals("request-1", response.get("requestId").getAsString());
        assertEquals("scene", response.get("action").getAsString());
        assertFalse(response.get("success").getAsBoolean());
        assertFalse(response.has("data"));
    }

    private static PetOfficeLocalProtocol.Request parse(String action, JsonObject payload) throws IOException {
        return PetOfficeLocalProtocol.parse(envelope(action, payload), PAGE);
    }

    private static String envelope(String action, JsonObject payload) {
        JsonObject value = new JsonObject();
        value.addProperty("requestId", "request-1");
        value.addProperty("action", action);
        value.addProperty("pageToken", PAGE);
        value.add("payload", payload);
        return value.toString();
    }

    private static JsonObject target() {
        JsonObject value = new JsonObject();
        value.addProperty("sourceKey", "instance/tab/binding");
        value.addProperty("routeGeneration", "route-1");
        value.addProperty("interactionId", "question-1");
        return value;
    }
}
