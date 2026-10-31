package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClawBotInteractionClearReviewTest {
    @Test
    public void clearingAResolvedQuestionPublishesTheEmptyInteractionRevision() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        try (var server = new ClawBotLocalIpcServer("gateway", 9, request -> {
            if ("SESSION_INTERACTION".equals(request.type())) {
                received.set(request.payload().get("interactionToken").getAsString());
            }
            JsonObject result = new JsonObject();
            result.addProperty("accepted", true);
            return new ClawBotIpcEnvelope(ClawBotIpcEnvelope.PROTOCOL_VERSION,
                    request.type() + "_RESULT", request.requestId(), "gateway", 9, result);
        }); var client = new ClawBotIdeClient(server.start(), "idea", 1)) {
            assertTrue(client.updateInteraction("tab", "message", "question-revision"));
            assertEquals("question-revision", received.get());
            assertTrue(client.updateInteraction("tab", "message", ""));
            assertEquals("", received.get());
            assertTrue(client.updateInteraction("tab", "message", null));
            assertEquals("", received.get());
            assertThrows(IllegalArgumentException.class, () -> client.updateInteraction("tab", "message", " "));
        }
    }
}
