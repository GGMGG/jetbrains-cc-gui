package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.AiDataProcessGate;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.storage.CodexStorageInventory;
import com.github.claudecodegui.storage.CodexStorageErrors;
import com.github.claudecodegui.storage.CodexStoragePaths;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only storage settings protocol with bounded, on-demand content reads. */
final class CodexStorageHandler {
    private static final Logger LOG = Logger.getInstance(CodexStorageHandler.class);
    static final String[] TYPES = {
        "get_ai_storage_usage", "cancel_ai_storage_scan", "list_ai_storage_sessions", "get_ai_storage_session", "reveal_ai_storage_session"
    };
    private final HandlerContext context;
    private final Map<String, AtomicBoolean> scans = new ConcurrentHashMap<>();
    private volatile boolean disposed;

    CodexStorageHandler(HandlerContext context) {
        this.context = context;
    }

    boolean handle(String action, String content) {
        if (!java.util.Arrays.asList(TYPES).contains(action)) {
            return false;
        }
        if (disposed) {
            return true;
        }
        ScanToken scan = action.equals("get_ai_storage_usage") ? prepareScan(content) : null;
        AppExecutorUtil.getAppExecutorService().execute(() -> execute(action, content, scan));
        return true;
    }

    private void execute(String action, String content, ScanToken scan) {
        JsonObject response = new JsonObject();
        response.addProperty("action", action);
        try {
            JsonObject request = parse(content);
            String requestId = required(request, "requestId");
            response.addProperty("requestId", requestId);
            if (scan != null && scan.error() != null) {
                throw new IOException(scan.error());
            }
            if (!"codex".equals(required(request, "provider"))) {
                throw new IOException("PROVIDER_UNSUPPORTED");
            }
            if (disposed || context.isDisposed()) {
                return;
            }
            if (action.equals("cancel_ai_storage_scan")) {
                cancelScan(required(request, "scanId"));
                response.add("data", new JsonObject());
            } else {
                CodexStoragePaths paths = CodexStoragePaths.current();
                JsonElement data = read(action, request, paths, scan);
                response.add("data", data);
            }
            response.addProperty("success", true);
        } catch (Exception error) {
            LOG.warn("[CodexStorage] Request failed: " + action, error);
            response.addProperty("success", false);
            response.addProperty("error", CodexStorageErrors.errorCode(error));
        } finally {
            finishScan(scan);
        }
        if (!disposed && !context.isDisposed() && (context.getProject() == null || !context.getProject().isDisposed())) {
            context.callJavaScript("onCodexStorageResponse", context.escapeJs(response.toString()));
        }
    }

    static Map<String, String> loadSessionTitles(NodeJsServiceCaller caller) {
        try {
            JsonElement result = JsonParser.parseString(caller.callNodeJsTitlesService("loadTitles"));
            if (!result.isJsonObject()) {
                return Map.of();
            }
            Map<String, String> titles = new LinkedHashMap<>();
            for (var entry : result.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonElement title = entry.getValue().getAsJsonObject().get("customTitle");
                if (title != null && title.isJsonPrimitive() && title.getAsJsonPrimitive().isString() && !title.getAsString().isBlank()) {
                    titles.put(entry.getKey(), title.getAsString());
                }
            }
            return titles;
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("[CodexStorage] Failed to load session titles; using stored history titles");
            return Map.of();
        }
    }

    private JsonObject read(String action, JsonObject request, CodexStoragePaths paths, ScanToken scan) throws Exception {
        try (AiDataProcessGate.ReadPermit permit = AiDataProcessGate.getInstance().tryAcquireReadPermit()) {
            if (permit == null) {
                throw new IOException("DATABASE_BUSY");
            }
            Map<String, String> titles = "list_ai_storage_sessions".equals(action)
                    ? loadSessionTitles(new NodeJsServiceCaller(context)) : Map.of();
            CodexStorageInventory inventory = new CodexStorageInventory(paths, titles);
            int limit = request.has("limit") ? request.get("limit").getAsInt() : 50;
            return switch (action) {
                case "list_ai_storage_sessions" -> request.has("page")
                        ? inventory.listPage(request.get("page").getAsInt(), limit, optional(request, "query"), optional(request, "sort"), optional(request, "filter"))
                        : inventory.list(optional(request, "cursor"), limit, optional(request, "query"), optional(request, "sort"), optional(request, "filter"));
                case "get_ai_storage_session" -> inventory.content(required(request, "sessionId"), optional(request, "cursor"), limit);
                case "reveal_ai_storage_session" -> revealSession(inventory, paths, required(request, "sessionId"));
                case "get_ai_storage_usage" -> inventory.usage(() -> scan.cancelled().get() || disposed || context.isDisposed());
                default -> throw new IOException("STORAGE_ACTION_UNSUPPORTED");
            };
        }
    }

    private JsonObject revealSession(CodexStorageInventory inventory, CodexStoragePaths paths, String sessionId) throws IOException {
        Path file = inventory.sessionFile(sessionId);
        CompletableFuture<Void> revealed = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                if (disposed || context.isDisposed() || (context.getProject() != null && context.getProject().isDisposed())
                        || !RevealFileAction.isSupported()) {
                    throw new IOException("STORAGE_LOCATION_UNAVAILABLE");
                }
                paths.revalidate();
                RevealFileAction.openFile(paths.existingFile(file));
                revealed.complete(null);
            } catch (Exception error) {
                revealed.completeExceptionally(error);
            }
        }, ModalityState.any());
        revealed.join();
        JsonObject result = new JsonObject();
        result.addProperty("sessionId", sessionId);
        return result;
    }

    // Register on dispatch, before worker scheduling, so an early cancel cannot be lost.
    ScanToken prepareScan(String content) {
        try {
            String id = required(parse(content), "requestId");
            AtomicBoolean flag = new AtomicBoolean();
            return scans.putIfAbsent(id, flag) == null ? new ScanToken(id, flag, null)
                    : new ScanToken(id, null, "REQUEST_ID_CONFLICT");
        } catch (IOException error) {
            return new ScanToken(null, null, "INVALID_STORAGE_REQUEST");
        }
    }

    void cancelScan(String id) {
        AtomicBoolean flag = scans.get(id);
        if (flag != null) {
            flag.set(true);
        }
    }

    void finishScan(ScanToken scan) {
        if (scan != null && scan.cancelled() != null) {
            scans.remove(scan.id(), scan.cancelled());
        }
    }

    record ScanToken(String id, AtomicBoolean cancelled, String error) { }

    void dispose() {
        disposed = true;
        scans.values().forEach(value -> value.set(true));
        scans.clear();
    }

    static JsonObject parse(String content) throws IOException {
        try {
            if (content == null || content.length() > 16 * 1024) {
                throw new IllegalArgumentException();
            }
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("INVALID_STORAGE_REQUEST", error);
        }
    }

    static String required(JsonObject request, String name) throws IOException {
        String value = optional(request, name);
        if (value == null || value.isBlank()) {
            throw new IOException("INVALID_STORAGE_REQUEST");
        }
        return value;
    }

    static String optional(JsonObject request, String name) throws IOException {
        if (!request.has(name) || request.get(name).isJsonNull()) {
            return null;
        }
        JsonElement value = request.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().length() > 1024) {
            throw new IOException("INVALID_STORAGE_REQUEST");
        }
        return value.getAsString();
    }
}
