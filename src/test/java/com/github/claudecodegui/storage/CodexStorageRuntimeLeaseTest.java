package com.github.claudecodegui.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CodexStorageRuntimeLeaseTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void pendingNativeResumePreventsFamilyDeletionBeforeAnySideEffect() throws Exception {
        var fixture = new CodexStorageFixture(temporary.newFolder().toPath());
        fixture.thread("parent", "/workspace", 10);
        fixture.thread("child", "/workspace", 10);
        fixture.sql("state_5.sqlite", "INSERT INTO thread_spawn_edges VALUES('parent','child','completed')");
        var parent = fixture.rollout("parent", null, false);
        var child = fixture.rollout("child", "parent", false);
        try (var resume = CodexStorageRuntimeLease.resume(fixture.root, "child", fixture.coordination)) {
            var result = fixture.service().deleteHistorySessions(List.of("parent"), UUID.randomUUID().toString(), false);
            assertEquals("FAILED_CLEAN", result.get("state").getAsString());
            assertEquals("SESSION_WRITER_BUSY", result.get("error").getAsString());
            assertTrue(Files.exists(parent));
            assertTrue(Files.exists(child));
            assertEquals(2, fixture.count("state_5.sqlite", "threads"));
        }
        var result = fixture.service().deleteHistorySessions(List.of("parent"), UUID.randomUUID().toString(), false);
        assertEquals("COMPLETED", result.get("state").getAsString());
        assertFalse(Files.exists(parent));
        assertFalse(Files.exists(child));
        IOException error = assertThrows(IOException.class,
                () -> CodexStorageRuntimeLease.resume(fixture.root, "child", fixture.coordination));
        assertEquals("SESSION_PERMANENTLY_DELETED", error.getMessage());
    }

    @Test
    public void anUnrelatedResumeDoesNotBlockSelectedSessionDeletion() throws Exception {
        var fixture = new CodexStorageFixture(temporary.newFolder().toPath());
        fixture.thread("selected", "/workspace", 10);
        var file = fixture.rollout("selected", null, false);
        try (var unrelated = CodexStorageRuntimeLease.resume(fixture.root, "unrelated", fixture.coordination)) {
            var result = fixture.service().deleteHistorySessions(List.of("selected"), UUID.randomUUID().toString(), false);
            assertEquals("COMPLETED", result.get("state").getAsString());
            assertFalse(Files.exists(file));
        }
    }

    @Test
    public void exclusiveDeletionRejectsNewResumesAndReleasesAfterClosing() throws Exception {
        var fixture = new CodexStorageFixture(temporary.newFolder().toPath());
        try (var deleting = CodexStorageRuntimeLease.deletion(fixture.paths, List.of("selected"), fixture.coordination)) {
            var error = assertThrows(IOException.class,
                    () -> CodexStorageRuntimeLease.resume(fixture.root, "selected", fixture.coordination));
            assertEquals("SESSION_WRITER_BUSY", error.getMessage());
        }
        try (var resumed = CodexStorageRuntimeLease.resume(fixture.root, "selected", fixture.coordination)) {
            assertThrows(IOException.class,
                    () -> CodexStorageRuntimeLease.deletion(fixture.paths, List.of("selected"), fixture.coordination));
        }
    }
}
