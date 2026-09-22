package com.github.claudecodegui.provider.minimax;

import com.github.claudecodegui.provider.common.MarkerCliBridge;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MiniMax Code CLI bridge.
 *
 * <p>No official SDK — spawns local {@code minimax} (mcode) via channel-manager
 * and maps its stream-json output onto the shared marker protocol.
 */
public class MiniMaxCliBridge extends MarkerCliBridge {

    public MiniMaxCliBridge() {
        super(MiniMaxCliBridge.class);
    }

    @Override
    protected String getProviderName() {
        return "minimax";
    }

    @Override
    protected String getStdinEnvKey() {
        return "MINIMAX_USE_STDIN";
    }

    @Override
    protected void configureExtraEnv(Map<String, String> env) {
        // Reserved for future MiniMax-specific env (e.g. MINIMAX_CODE_HOME).
    }

    @Override
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        try {
            return new MiniMaxHistoryReader().getSessionMessages(sessionId, cwd);
        } catch (Exception e) {
            LOG.warn("[MiniMax] Failed to load session messages: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<JsonObject> getSessionMessages(String sessionId, String cwd,
                                               java.util.function.BooleanSupplier cancellation) {
        try {
            if (cancellation.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("History loading was cancelled");
            }
            List<JsonObject> messages = new MiniMaxHistoryReader().getSessionMessages(sessionId, cwd, cancellation);
            if (cancellation.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("History loading was cancelled");
            }
            return messages;
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load MiniMax session history", e);
        }
    }
}
