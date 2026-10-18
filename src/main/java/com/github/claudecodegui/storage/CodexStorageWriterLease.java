package com.github.claudecodegui.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Uses Codex's native writer/publication lock namespace, in addition to IDE admission and SQLite locks. */
final class CodexStorageWriterLease implements AutoCloseable {
    private final List<FileChannel> channels = new ArrayList<>();
    private final List<FileLock> locks = new ArrayList<>();

    static CodexStorageWriterLease acquire(CodexStoragePaths paths) throws IOException {
        CodexStorageWriterLease lease = new CodexStorageWriterLease();
        try {
            paths.revalidate();
            Path temporary = lease.directory(paths, ".tmp");
            lease.lock(paths, temporary.resolve("rollout-maintenance.lock"), true);
            Path writers = lease.directory(paths, "thread-writer-locks");
            lease.lock(paths, writers.resolve(".coordination.lock"), true);
            // Coordination excludes new writers and publication until all source work is finished.
            // Probe existing writers while holding it; never wait for or interrupt a writer.
            try (var files = Files.list(writers)) {
                for (Path file : files.filter(path -> !path.getFileName().toString().equals(".coordination.lock")
                        && path.getFileName().toString().endsWith(".lock")).sorted().toList()) {
                    if (active(paths, file)) {
                        throw new IOException("DATABASE_BUSY");
                    }
                }
            }
            return lease;
        } catch (IOException | RuntimeException error) {
            try {
                lease.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private Path directory(CodexStoragePaths paths, String name) throws IOException {
        Path directory = paths.root().resolve(name);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory)) {
            throw new IOException("UNSAFE_STORAGE_PATH");
        }
        return directory;
    }

    static java.util.Set<String> occupiedSessions(CodexStoragePaths paths) throws IOException {
        var result = new java.util.LinkedHashSet<String>();
        Path directory = paths.root().resolve("thread-writer-locks");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return result;
        }
        if (!directory.toRealPath().equals(directory)) {
            throw new IOException("UNSAFE_STORAGE_PATH");
        }
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> !path.getFileName().toString().equals(".coordination.lock")
                    && path.getFileName().toString().endsWith(".lock")).toList()) {
                if (active(paths, file)) {
                    String name = file.getFileName().toString();
                    String id = name.substring(0, name.length() - ".lock".length());
                    CodexStoragePaths.requireSessionId(id);
                    result.add(id);
                }
            }
        }
        return result;
    }

    private static boolean active(CodexStoragePaths paths, Path file) throws IOException {
        paths.existingFile(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (FileLock lock = channel.tryLock()) {
                return lock == null;
            } catch (OverlappingFileLockException error) {
                return true;
            }
        } catch (java.nio.file.NoSuchFileException ignored) {
            return false;
        }
    }

    private void lock(CodexStoragePaths paths, Path file, boolean create) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            paths.existingFile(file);
        }
        FileChannel channel = create
                ? FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                : FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        channels.add(channel);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("DATABASE_BUSY");
            }
            locks.add(lock);
        } catch (OverlappingFileLockException error) {
            throw new IOException("DATABASE_BUSY", error);
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (int i = locks.size() - 1; i >= 0; i--) {
            try {
                locks.get(i).close();
            } catch (IOException error) {
                failure = error;
            }
        }
        for (int i = channels.size() - 1; i >= 0; i--) {
            try {
                channels.get(i).close();
            } catch (IOException error) {
                failure = error;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
