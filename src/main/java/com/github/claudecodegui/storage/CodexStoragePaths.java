package com.github.claudecodegui.storage;

import com.github.claudecodegui.bridge.NodeDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Resolves the existing Codex home without importing settings or running migration recovery. */
public final class CodexStoragePaths {
    public static final String HISTORY_DATABASE = "thread_history_1.sqlite";
    public static final Set<String> DATABASES = Set.of(
            HISTORY_DATABASE, "state_5.sqlite", "goals_1.sqlite", "queue_1.sqlite", "memories_1.sqlite", "logs_2.sqlite");
    private final Path canonical;
    private final Path root;
    private final String identity;

    public CodexStoragePaths(Path canonical) throws IOException {
        this.canonical = canonical.toAbsolutePath().normalize();
        this.root = this.canonical.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("CODEX_HOME_UNAVAILABLE");
        }
        this.identity = identity(root);
    }

    public static CodexStoragePaths current() throws IOException {
        return new CodexStoragePaths(Path.of(NodeDetector.resolveHomeForFileOps(), ".codex"));
    }

    public Path canonical() {
        return canonical;
    }

    public Path root() {
        return root;
    }

    public String identity() {
        return identity;
    }

    public void revalidate() throws IOException {
        if (!canonical.toRealPath().equals(root) || !identity(canonical).equals(identity)) {
            throw new IOException("STORAGE_IDENTITY_CHANGED");
        }
    }

    public Path existingFile(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("UNSAFE_STORAGE_PATH");
        }
        Path real = normalized.toRealPath();
        if (!real.equals(normalized) || !real.startsWith(root)) {
            throw new IOException("UNSAFE_STORAGE_PATH");
        }
        return real;
    }

    public static String identity(Path path) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            try {
                return path.toRealPath() + "|" + CodexStorageWindowsIdentity.read(path);
            } catch (LinkageError error) {
                throw new IOException("STORAGE_IDENTITY_UNSUPPORTED", error);
            }
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return path.toRealPath() + "|" + attributes.fileKey() + "|" + attributes.creationTime().toMillis();
    }

    static Object physicalFileKey(Path path, BasicFileAttributes attributes) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            return CodexStorageWindowsIdentity.read(path);
        }
        return attributes.fileKey() == null ? path.toRealPath() : attributes.fileKey();
    }

    public static void requireSessionId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || id.equals("..")) {
            throw new IOException("INVALID_SESSION_ID");
        }
    }
}
