package com.github.claudecodegui.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Reads actual WAL fixtures without enabling any storage mutation protocol. */
public class CodexStorageReadOnlyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readOnlyConnectionsNeverCreateAMissingDatabase() throws Exception {
        var file = temporary.getRoot().toPath().resolve("missing.sqlite");
        assertThrows(SQLException.class, () -> CodexStorageDatabase.open(file, true));
        assertFalse(Files.exists(file));
    }

    @Test public void readsCommittedWalRowsAndRejectsWritesWithoutChangingTheMainDatabase() throws Exception {
        var fixture = new CodexStorageFixture(temporary.newFolder().toPath());
        var file = fixture.root.resolve("state_5.sqlite");
        fixture.thread("wal-visible", "/fixture", 32);
        try (var writer = CodexStorageDatabase.open(file, false)) {
            try (var statement = writer.createStatement(); var result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                assertTrue(result.next());
                assertEquals("wal", result.getString(1));
            }
            CodexStorageDatabase.execute(writer, "PRAGMA wal_autocheckpoint=0");
            CodexStorageDatabase.execute(writer, "UPDATE threads SET title='Committed WAL title' WHERE id='wal-visible'");
            assertTrue(Files.size(file.resolveSibling("state_5.sqlite-wal")) > 0);
            byte[] before = Files.readAllBytes(file);
            try (var reader = CodexStorageDatabase.open(file, true)) {
                assertEquals(1, CodexStorageDatabase.scalar(reader, "SELECT count(*) FROM threads"));
                assertThrows(SQLException.class, () -> CodexStorageDatabase.execute(reader, "DELETE FROM threads"));
                assertEquals(1, CodexStorageDatabase.scalar(reader, "SELECT count(*) FROM threads"));
            }
            var inventory = new CodexStorageInventory(fixture.paths);
            assertEquals("Committed WAL title", inventory.snapshot().sessions().get("wal-visible").title);
            assertFalse(inventory.content("wal-visible", null, 10).getAsJsonArray("records").isEmpty());
            assertTrue("Read-only inventory changed the main database", Arrays.equals(before, Files.readAllBytes(file)));
        }
    }
}
