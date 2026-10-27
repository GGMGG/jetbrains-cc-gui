package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.storage.CodexStorageErrors;
import com.github.claudecodegui.storage.CodexStorageMaintenance;
import com.github.claudecodegui.storage.CodexStoragePaths;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.util.Arrays;

/** Database maintenance is independent of the read-only inventory protocol. */
final class CodexStorageMaintenanceHandler {
    private static final Logger LOG = Logger.getInstance(CodexStorageMaintenanceHandler.class);
    static final String[] TYPES = {
        "get_ai_storage_database_status", "get_ai_storage_mutation_status", "compact_ai_storage_database",
        "get_ai_storage_maintenance_operation", "list_ai_storage_maintenance_operations",
        "preview_ai_storage_orphans", "clean_ai_storage_orphans"
    };
    private final HandlerContext context;

    CodexStorageMaintenanceHandler(HandlerContext context) {
        this.context = context;
    }

    boolean handle(String action, String content) {
        if (!Arrays.asList(TYPES).contains(action)) {
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
            JsonObject request = CodexStorageHandler.parse(content);
            response.addProperty("requestId", CodexStorageHandler.required(request, "requestId"));
            if (!"codex".equals(CodexStorageHandler.required(request, "provider"))) {
                throw new IOException("PROVIDER_UNSUPPORTED");
            }
            CodexStorageMaintenance maintenance = new CodexStorageMaintenance(CodexStoragePaths.current());
            JsonElement data = switch (action) {
                case "get_ai_storage_database_status" -> maintenance.databaseStatus();
                case "get_ai_storage_mutation_status" -> CodexStorageDeletionHandler.mutationStatus(
                        maintenance, context.getSession(), context.getCodexSDKBridge());
                case "compact_ai_storage_database" -> maintenance.compact(
                        CodexStorageHandler.required(request, "databaseId"), CodexStorageHandler.required(request, "operationId"));
                case "preview_ai_storage_orphans" -> maintenance.previewOrphans();
                case "clean_ai_storage_orphans" -> maintenance.cleanOrphans(CodexStorageHandler.required(request, "databaseId"),
                        CodexStorageHandler.required(request, "previewToken"), CodexStorageHandler.required(request, "operationId"));
                case "get_ai_storage_maintenance_operation" -> maintenance.operation(CodexStorageHandler.required(request, "operationId"));
                case "list_ai_storage_maintenance_operations" -> maintenance.operations();
                default -> throw new IOException("STORAGE_ACTION_UNSUPPORTED");
            };
            response.add("data", data);
            response.addProperty("success", true);
        } catch (Exception error) {
            LOG.warn("[CodexStorage] Maintenance request failed: " + action, error);
            response.addProperty("success", false);
            response.addProperty("error", CodexStorageErrors.errorCode(error));
        }
        if (!context.isDisposed() && (context.getProject() == null || !context.getProject().isDisposed())) {
            context.callJavaScript("onCodexStorageResponse", context.escapeJs(response.toString()));
        }
    }
}
