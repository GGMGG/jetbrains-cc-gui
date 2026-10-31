package com.github.claudecodegui.storage;

import com.github.claudecodegui.settings.AiDataMigrationCoordinator;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/** Fences plugin resume requests against deletion until native writer ownership is established. */
public final class CodexStorageRuntimeLease implements AutoCloseable {
    private static final Logger LOG = Logger.getInstance(CodexStorageRuntimeLease.class);
    private final List<AutoCloseable> locks = new ArrayList<>();

    public static CodexStorageRuntimeLease resume(Path home, String id) throws IOException {
        return resume(home, id, AiDataMigrationCoordinator.defaultCoordinationDirectory());
    }

    public static CodexStorageRuntimeLease resume(Path home, String id, Path coordination) throws IOException {
        CodexStorageRuntimeLease lease = new CodexStorageRuntimeLease();
        if (CodexMaintenanceJournal.blocksAdmission(coordination)) {
            throw new IOException("AI_DATA_MAINTENANCE_PENDING");
        }
        if (id == null || id.isBlank()) {
            return lease;
        }
        CodexStoragePaths.requireSessionId(id);
        try {
            var lock = coordinator(home, id, coordination).tryAcquireProcessLock();
            if (lock == null) {
                throw new IOException("SESSION_WRITER_BUSY");
            }
            lease.locks.add(lock);
            // The shared lease stays held until the native request settles. Deletion obtains
            // the exclusive counterpart before writing its tombstone or removing any data.
            new CodexMaintenanceJournal(coordination).requireSessionAvailable(home, id);
            return lease;
        } catch (IOException | RuntimeException error) {
            lease.close();
            throw error;
        }
    }

    static CodexStorageRuntimeLease deletion(CodexStoragePaths paths, Collection<String> ids, Path coordination) throws IOException {
        CodexStorageRuntimeLease lease = new CodexStorageRuntimeLease();
        try {
            for (String id : ids.stream().distinct().sorted().toList()) {
                CodexStoragePaths.requireSessionId(id);
                var lock = coordinator(paths.root(), id, coordination).tryAcquire("codex-storage");
                if (lock == null) {
                    throw new IOException("SESSION_WRITER_BUSY");
                }
                lease.locks.add(lock);
            }
            return lease;
        } catch (IOException | RuntimeException error) {
            lease.close();
            throw error;
        }
    }

    private static AiDataMigrationCoordinator coordinator(Path home, String id, Path coordination) throws IOException {
        Path root = Files.exists(home) ? home.toRealPath() : home.toAbsolutePath().normalize();
        try {
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(root.toString().getBytes(StandardCharsets.UTF_8)));
            return new AiDataMigrationCoordinator(coordination.resolve("codex-resume-locks").resolve(key).resolve(id));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public void close() {
        for (int index = locks.size() - 1; index >= 0; index--) {
            try {
                locks.get(index).close();
            } catch (Exception error) {
                LOG.warn("[CodexStorage] Failed to release runtime admission", error);
            }
        }
        locks.clear();
    }
}
