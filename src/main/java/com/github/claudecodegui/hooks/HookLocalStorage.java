package com.github.claudecodegui.hooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Private per-user hook state; project configuration never contains recovery copies. */
final class HookLocalStorage {

    private HookLocalStorage() {
    }

    static Path directory(Path userHome, String kind, Path source) {
        Path identity = source.toAbsolutePath().normalize();
        try {
            identity = identity.toRealPath();
        } catch (IOException ignored) {
            // A missing config still needs a stable bucket for creating its first state entry.
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));
            return userHome.toAbsolutePath().normalize().resolve(".cc-gui").resolve("hooks")
                    .resolve(kind).resolve(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static void prepare(Path userHome, Path directory) throws IOException {
        Path home = userHome.toAbsolutePath().normalize();
        if (!directory.startsWith(home.resolve(".cc-gui").resolve("hooks"))) {
            throw new IOException("Invalid private hook storage path");
        }
        Path current = home;
        for (Path component : home.relativize(directory)) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } catch (UnsupportedOperationException ignored) {
                    Files.createDirectory(current);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another source can initialize the shared parent concurrently.
                }
            }
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Private hook storage must not be redirected");
            }
            if (Files.getFileAttributeView(current, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(current, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    static void restrictFile(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
