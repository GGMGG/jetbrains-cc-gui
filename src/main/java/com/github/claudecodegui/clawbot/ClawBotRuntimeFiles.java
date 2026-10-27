package com.github.claudecodegui.clawbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Private local discovery files. Existing unsafe paths/permissions are rejected, never repaired silently. */
final class ClawBotRuntimeFiles {
    private final Path root;

    ClawBotRuntimeFiles(Path root) { this.root = root.toAbsolutePath().normalize(); }

    void prepare() throws IOException {
        Path parent = root.getParent();
        if (parent == null) {
            throw new IOException("CLAWBOT_RUNTIME_UNAVAILABLE");
        }
        for (Path path = root; path != null; path = path.getParent()) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                directory(path);
            }
        }
        Files.createDirectories(parent);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(root);
                restrict(root, true);
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // A simultaneous initializer must still pass the same permission check below.
            }
        }
        validateRoot();
    }

    Path path(String name) throws IOException {
        if (!Set.of("gateway.auth", "leader.json", "gateway.lock", "progress-settings.json", "execution-journal.json",
                "ide-execution-journal.json", "ide-execution-journal.json.lock", "transport-state.json", "pending-deliveries.enc",
                "reply-recovery.enc", "reply-capabilities.enc", "outbound-protection.json", "outbound-receipts.json").contains(name)) {
            throw new IOException("CLAWBOT_RUNTIME_FILE_INVALID");
        }
        return root.resolve(name);
    }

    void validateRoot() throws IOException {
        for (Path path = root; path != null; path = path.getParent()) {
            directory(path);
        }
        permissions(root);
    }

    void validateFile(Path file) throws IOException {
        validateRoot();
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || !Files.getOwner(root, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("CLAWBOT_RUNTIME_FILE_UNSAFE");
        }
        permissions(file);
    }

    byte[] read(String name, int limit) throws IOException {
        validateRoot();
        Path file = path(name);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        validateFile(file);
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("CLAWBOT_RUNTIME_FILE_TOO_LARGE");
            }
            return bytes;
        }
    }

    void write(String name, byte[] bytes) throws IOException {
        validateRoot();
        Path target = path(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            validateFile(target);
        }
        Path temporary = Files.createTempFile(root, ".claw-runtime-", ".tmp");
        try {
            restrict(temporary, false);
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void directory(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("CLAWBOT_RUNTIME_UNAVAILABLE");
        }
    }

    void remove(String name) throws IOException {
        validateRoot();
        Path target = path(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            validateFile(target);
            Files.delete(target);
        }
    }

    static void restrict(Path path, boolean directory) throws IOException {
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            Files.setPosixFilePermissions(path, permissions);
        }
    }

    private static void permissions(Path path) throws IOException {
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            for (var entry : acl.getAcl()) {
                String name = entry.principal().getName();
                boolean privileged = name.equalsIgnoreCase("NT AUTHORITY\\SYSTEM") || name.equalsIgnoreCase("BUILTIN\\Administrators");
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner) && !privileged) {
                    throw new IOException("CLAWBOT_RUNTIME_PERMISSIONS_UNSAFE");
                }
            }
        } else if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) {
            throw new IOException("CLAWBOT_RUNTIME_PERMISSIONS_UNSAFE");
        }
    }
}
