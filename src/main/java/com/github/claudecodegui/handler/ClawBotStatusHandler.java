package com.github.claudecodegui.handler;

import com.github.claudecodegui.clawbot.ClawBotGatewayRuntimeService;
import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.JsonObject;

/** Exposes a sanitized, read-only gateway status to the settings webview. */
public final class ClawBotStatusHandler extends BaseMessageHandler {

    private static final String[] SUPPORTED_TYPES = {"get_clawbot_status"};

    public ClawBotStatusHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        if (!matchesType(type, SUPPORTED_TYPES)) {
            return false;
        }
        callJavaScript("window.onClawBotStatus", escapeJs(statusSnapshot().toString()));
        return true;
    }

    private static JsonObject statusSnapshot() {
        try {
            return ClawBotGatewayRuntimeService.getInstance().statusSnapshot();
        } catch (RuntimeException ignored) {
            JsonObject status = new JsonObject();
            status.addProperty("state", "STOPPED");
            status.addProperty("transport", "MOCK");
            status.addProperty("transportState", "STOPPED");
            status.addProperty("sessionCount", 0);
            status.addProperty("senderAccessCount", 0);
            status.addProperty("bindingState", "UNKNOWN");
            status.addProperty("bindingRevision", 0);
            status.addProperty("bindingDiagnostic", "BINDING_STATUS_UNAVAILABLE");
            status.addProperty("pairingState", "IDLE");
            status.addProperty("pairingExpiresAt", 0);
            status.addProperty("pairingAttempt", 0);
            status.add("pairing", new JsonObject());
            return status;
        }
    }
}
