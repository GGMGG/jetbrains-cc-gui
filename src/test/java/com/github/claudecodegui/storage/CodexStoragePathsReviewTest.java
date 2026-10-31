package com.github.claudecodegui.storage;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CodexStoragePathsReviewTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void linkedCanonicalHomeUsesTheResolvedDirectoryIdentityAndRejectsRetargeting() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
        Path parent = temporary.getRoot().toPath();
        Path target = Files.createDirectory(parent.resolve("physical"));
        Path canonical = Files.createSymbolicLink(parent.resolve("canonical"), target);
        try {
            CodexStoragePaths paths = new CodexStoragePaths(canonical);
            paths.revalidate();
            assertEquals(CodexStoragePaths.identity(target), paths.identity());
            Files.delete(canonical);
            Files.createSymbolicLink(canonical, Files.createDirectory(parent.resolve("other")));
            assertEquals("STORAGE_IDENTITY_CHANGED", assertThrows(IOException.class, paths::revalidate).getMessage());
        } finally {
            Files.deleteIfExists(canonical);
        }
    }

    @Test
    public void replacingThePhysicalDirectoryAtTheSamePathStillInvalidatesItsIdentity() throws Exception {
        Path parent = temporary.getRoot().toPath();
        Path target = Files.createDirectory(parent.resolve("physical"));
        CodexStoragePaths paths = new CodexStoragePaths(target);
        Files.move(target, parent.resolve("original"));
        Files.createDirectory(target);
        assertEquals("STORAGE_IDENTITY_CHANGED", assertThrows(IOException.class, paths::revalidate).getMessage());
    }
}
