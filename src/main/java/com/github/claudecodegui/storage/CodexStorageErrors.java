package com.github.claudecodegui.storage;

import java.sql.SQLException;

/** Stable public error codes without exposing database or file contents. */
public final class CodexStorageErrors {
    private CodexStorageErrors() { }

    public static String errorCode(Throwable error) {
        if (error.getCause() != null && (error instanceof java.util.concurrent.CompletionException || error instanceof IllegalStateException)) {
            return errorCode(error.getCause());
        }
        if (error instanceof SQLException sql && (sql.getErrorCode() == 5 || sql.getErrorCode() == 6)) {
            return "DATABASE_BUSY";
        }
        String message = error.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]+") ? message : "STORAGE_OPERATION_FAILED";
    }
}
