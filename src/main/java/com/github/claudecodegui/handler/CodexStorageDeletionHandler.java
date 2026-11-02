package com.github.claudecodegui.handler;

import com.github.claudecodegui.cache.SessionIndexCache;
import com.github.claudecodegui.cache.SessionIndexManager;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.storage.CodexStorageActivity;
import com.github.claudecodegui.storage.CodexStorageErrors;
import com.github.claudecodegui.storage.CodexStorageMaintenance;
import com.github.claudecodegui.storage.CodexStoragePaths;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.github.claudecodegui.handler.CodexStorageHandler.parse;
import static com.github.claudecodegui.handler.CodexStorageHandler.required;

/** Confirmed session deletion, separate from inventory reads and database maintenance. */
final class CodexStorageDeletionHandler {
    private static final Logger LOG = Logger.getInstance(CodexStorageDeletionHandler.class);
    static final String[] TYPES = {"delete_ai_storage_sessions"};
    private final HandlerContext context;

    CodexStorageDeletionHandler(HandlerContext context) {
        this.context = context;
    }

    boolean handle(String action, String content) {
        if (!"delete_ai_storage_sessions".equals(action)) {
            return false;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> execute(action, content));
        return true;
    }

    private void execute(String action, String content) {
        if (context.isDisposed()) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("action", action);
        try {
            JsonObject request = parse(content);
            response.addProperty("requestId", required(request, "requestId"));
            if (!"codex".equals(required(request, "provider"))) {
                throw new IOException("PROVIDER_UNSUPPORTED");
            }
            CodexStorageMaintenance maintenance = new CodexStorageMaintenance(CodexStoragePaths.current());
            response.add("data", deleteSessions(request, maintenance));
            response.addProperty("success", true);
        } catch (Exception error) {
            LOG.warn("[CodexStorage] Session deletion failed", error);
            response.addProperty("success", false);
            response.addProperty("error", CodexStorageErrors.errorCode(error));
        }
        if (!context.isDisposed() && (context.getProject() == null || !context.getProject().isDisposed())) {
            context.callJavaScript("onCodexStorageResponse", context.escapeJs(response.toString()));
        }
    }

    private JsonObject deleteSessions(JsonObject request, CodexStorageMaintenance maintenance) throws Exception {
        JsonObject status = deleteSessionData(request, maintenance, context.getSession(), context.getCodexSDKBridge());
        if (status.has("result") && status.getAsJsonObject("result").has("deletedSessionIds")) {
            boolean metadataClean = true;
            NodeJsServiceCaller caller = new NodeJsServiceCaller(context);
            for (JsonElement deleted : status.getAsJsonObject("result").getAsJsonArray("deletedSessionIds")) {
                metadataClean &= cleanupSessionMetadata(caller, deleted.getAsString());
            }
            try {
                SessionIndexCache.getInstance().clearAllCodexCache();
                SessionIndexManager.getInstance().clearAllCodexIndex();
            } catch (Exception error) {
                metadataClean = false;
                LOG.warn("[CodexStorage] Failed to clear session indexes", error);
            }
            try {
                status = maintenance.finishMetadata(status.get("operationId").getAsString(), metadataClean);
            } catch (Exception error) {
                LOG.warn("[CodexStorage] Metadata receipt remains pending", error);
            }
        }
        return status;
    }

    static JsonObject deleteSessionData(JsonObject request, CodexStorageMaintenance maintenance,
                                       ClaudeSession session, CodexSDKBridge bridge) throws Exception {
        List<String> ids = deletionTargets(request);
        String operationId = required(request, "operationId");
        try {
            if (!java.util.UUID.fromString(operationId).toString().equals(operationId)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("INVALID_STORAGE_REQUEST", error);
        }
        requireIdleSession(session, ids);
        boolean history = historyDeletionRequested(request);
        releaseIdleHistorySession(session, ids, bridge).join();
        return history ? maintenance.deleteHistorySessions(ids, operationId, true) : maintenance.deleteSessions(ids, operationId, false, true);
    }

    static JsonObject mutationStatus(CodexStorageMaintenance maintenance, ClaudeSession session, CodexSDKBridge bridge) {
        JsonObject status = maintenance.mutationStatus();
        if (session != null && bridge != null && "codex".equals(session.getProvider()) && session.getSessionId() != null
                && bridge.ownsCodexThread(session.getSessionId()) && !session.isBusy()
                && !session.getState().getExecutionState().hasOwnedPendingWork()) {
            // Our idle target can be unloaded by the delete handler. Unknown owners remain protected.
            status.getAsJsonArray("writerSessionIds").asList().removeIf(id -> id.getAsString().equals(session.getSessionId()));
        }
        return status;
    }

    static boolean historyDeletionRequested(JsonObject request) throws IOException {
        if (!request.has("origin")) {
            return false;
        }
        JsonElement origin = request.get("origin");
        if (!origin.isJsonPrimitive() || !origin.getAsJsonPrimitive().isString()
                || !List.of("history", "storage").contains(origin.getAsString())) {
            throw new IOException("INVALID_STORAGE_REQUEST");
        }
        return origin.getAsString().equals("history");
    }

    static CompletableFuture<Void> releaseIdleHistorySession(ClaudeSession session, Collection<String> ids,
                                                             CodexSDKBridge bridge) throws IOException {
        requireIdleSession(session, ids);
        if (session == null || bridge == null || !"codex".equals(session.getProvider()) || !ids.contains(session.getSessionId())
                || !bridge.ownsCodexThread(session.getSessionId())) {
            return CompletableFuture.completedFuture(null);
        }
        String sessionId = session.getSessionId();
        String channelId = session.getChannelId();
        String cwd = session.getCwd();
        return bridge.releaseIdleCodexThread(channelId, cwd, sessionId).handle((result, failure) -> {
            if (failure != null || result == null || result.has("error")) {
                throw new java.util.concurrent.CompletionException(new IOException("SESSION_RUNNING", failure));
            }
            try {
                if (!java.util.Objects.equals(sessionId, session.getSessionId())
                        || !"codex".equals(session.getProvider()) || !java.util.Objects.equals(channelId, session.getChannelId())) {
                    throw new IOException("SELECTED_DATA_CHANGED");
                }
                requireIdleSession(session, ids);
            } catch (IOException error) {
                throw new java.util.concurrent.CompletionException(error);
            }
            return null;
        });
    }

    static List<String> deletionTargets(JsonObject request) throws IOException {
        JsonElement selected = request.get("sessionIds");
        if (selected == null || !selected.isJsonArray() || selected.getAsJsonArray().isEmpty() || selected.getAsJsonArray().size() > 1000) {
            throw new IOException("INVALID_SESSION_SELECTION");
        }
        List<String> ids = new ArrayList<>();
        for (JsonElement item : selected.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IOException("INVALID_SESSION_SELECTION");
            }
            String id = item.getAsString();
            CodexStoragePaths.requireSessionId(id);
            ids.add(id);
        }
        return ids;
    }

    static void requireIdleSession(ClaudeSession session, Collection<String> ids) throws IOException {
        if (session != null && "codex".equals(session.getProvider()) && ids.contains(session.getSessionId())
                && (session.isBusy() || session.getState().getExecutionState().hasOwnedPendingWork())) {
            throw new IOException("SESSION_RUNNING");
        }
        if (!java.util.Collections.disjoint(ids, CodexStorageActivity.runningSessionIds())) {
            throw new IOException("SESSION_RUNNING");
        }
    }

    static boolean cleanupSessionMetadata(NodeJsServiceCaller caller, String sessionId) {
        boolean favoritesClean = false;
        boolean titleClean = false;
        try {
            favoritesClean = metadataSucceeded(caller.callNodeJsFavoritesService("removeFavorite", sessionId));
        } catch (Exception error) {
            LOG.warn("[CodexStorage] Failed to clean up session favorite");
        }
        try {
            titleClean = metadataSucceeded(caller.callNodeJsDeleteTitle(sessionId));
        } catch (Exception error) {
            LOG.warn("[CodexStorage] Failed to clean up session title");
        }
        return favoritesClean && titleClean;
    }

    private static boolean metadataSucceeded(String response) {
        JsonElement result = JsonParser.parseString(response);
        JsonElement success = result.isJsonObject() ? result.getAsJsonObject().get("success") : result;
        return success != null && success.isJsonPrimitive() && success.getAsJsonPrimitive().isBoolean() && success.getAsBoolean();
    }

}
