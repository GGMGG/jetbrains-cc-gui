package com.github.claudecodegui.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class CodexStorageSchemaEvolutionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void codex166SchemaRemainsVisibleToReadOnlyInventoryAndMaintenance() throws Exception {
        var f = codex166Fixture();
        f.thread("history", "/project", 100);
        f.rollout("history", null, false);
        try (var connection = CodexStorageDatabase.open(f.root.resolve("state_5.sqlite"), true)) {
            assertEquals("f2d8db6a60f7dafdfd8050d02214f060baca0527f58915afa9c56b9624704c89",
                    CodexStorageDatabase.fingerprint(connection));
        }
        var snapshot = new CodexStorageInventory(f.paths).snapshot();
        assertTrue(snapshot.errors().toString(), snapshot.errors().isEmpty());
        assertTrue(snapshot.sessions().containsKey("history"));
        assertEquals("READ_ONLY_STORAGE_VIEW", snapshot.sessions().get("history").deletionUnavailableReason);
        assertTrue(f.service().databaseStatus().toString(), f.service().databaseStatus().get("supported").getAsBoolean());
    }

    @Test public void missingRequiredColumnsStillRejectBeforeDeletingSelectedData() throws Exception {
        var f = codex166Fixture();
        f.thread("remove", "/project", 100);
        var rollout = f.rollout("remove", null, false);
        f.sql("queue_1.sqlite", "ALTER TABLE queued_items RENAME COLUMN thread_id TO incompatible_thread_id");
        var result = f.service().deleteHistorySessions(List.of("remove"));
        assertEquals(result.toString(), "FAILED_CLEAN", result.get("state").getAsString());
        assertTrue(Files.exists(rollout));
        assertEquals(1L, f.count("state_5.sqlite", "threads"));
        assertEquals(1L, f.count(CodexStoragePaths.HISTORY_DATABASE, "thread_items"));
        assertFalse(new CodexMaintenanceJournal(f.coordination).hasPending());
    }

    @Test public void schemaChangesDuringInterruptedDeletionStillRequireReconciliation() throws Exception {
        var f = codex166Fixture();
        f.thread("remove", "/project", 100);
        var rollout = f.rollout("remove", null, false);
        String operationId = UUID.randomUUID().toString();
        var interrupted = f.service(checkpoint -> {
            if (checkpoint.equals("database-commit")) {
                throw new IllegalStateException("Simulated interruption");
            }
        }).deleteHistorySessions(List.of("remove"), operationId, false);
        assertEquals(interrupted.toString(), "RECONCILIATION_REQUIRED", interrupted.get("state").getAsString());
        f.sql("state_5.sqlite", "CREATE INDEX client_title_index ON threads(title)");
        var resumed = f.service().deleteHistorySessions(List.of("remove"), operationId, false);
        assertEquals(resumed.toString(), "RECONCILIATION_REQUIRED", resumed.get("state").getAsString());
        assertEquals("STORAGE_IDENTITY_CHANGED", resumed.get("error").getAsString());
        assertTrue(Files.exists(rollout));
        assertEquals(1L, f.count("state_5.sqlite", "threads"));
        assertEquals(1L, f.count(CodexStoragePaths.HISTORY_DATABASE, "thread_items"));
    }

    @Test public void additionalHistoryIndexAllowsCompactionWithoutChangingContent() throws Exception {
        var f = codex166Fixture();
        f.thread("keep", "/project", 1000);
        f.sql(CodexStoragePaths.HISTORY_DATABASE, "CREATE INDEX client_turn_index ON thread_items(turn_id)");
        String before;
        try (var connection = CodexStorageDatabase.open(f.root.resolve(CodexStoragePaths.HISTORY_DATABASE), true)) {
            before = CodexStorageDatabase.contentDigest(connection);
        }
        var service = f.service();
        var status = service.databaseStatus();
        assertTrue(status.toString(), status.get("supported").getAsBoolean());
        var result = service.compact(status.get("databaseId").getAsString(), UUID.randomUUID().toString());
        assertEquals(result.toString(), "COMPLETED", result.get("state").getAsString());
        try (var connection = CodexStorageDatabase.open(f.root.resolve(CodexStoragePaths.HISTORY_DATABASE), true)) {
            assertEquals(before, CodexStorageDatabase.contentDigest(connection));
        }
    }

    private CodexStorageFixture codex166Fixture() throws Exception {
        var fixture = new CodexStorageFixture(temporary.newFolder().toPath());
        fixture.sql("state_5.sqlite", "ALTER TABLE threads ADD COLUMN attached_thread_parent_id TEXT");
        fixture.sql("state_5.sqlite", "CREATE INDEX idx_threads_attached_parent ON threads(attached_thread_parent_id) WHERE attached_thread_parent_id IS NOT NULL");
        return fixture;
    }
}
