package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
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
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/** Current-user files with an explicit filename/byte budget, independent of IPC and AI storage. */
final class PetOfficePrivateFiles {
    private final Path directory;
    private final java.util.Map<String, Integer> limits;

    PetOfficePrivateFiles(Path directory, java.util.Map<String, Integer> limits) {
        this.directory = directory.toAbsolutePath().normalize();
        this.limits = java.util.Map.copyOf(limits);
        if (limits.isEmpty() || limits.keySet().stream().anyMatch(name -> !name.matches("[a-z0-9][a-z0-9.-]{0,79}"))
                || limits.values().stream().anyMatch(limit -> limit < 1 || limit > 4 * 1024 * 1024)) {
            throw new IllegalArgumentException("Invalid private file budget");
        }
    }

    JsonObject readPrivate(String name) throws IOException {
        byte[] bytes = readBytes(name);
        return bytes == null ? null : PetOfficeJsonCodec.parse(bytes);
    }

    byte[] readBytes(String name) throws IOException {
        UserPrincipal owner = validateDirectory();
        Path file = privatePath(name);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        validateFile(file, owner);
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit(name) + 1);
            if (bytes.length > limit(name)) {
                throw new IOException("OFFICE_ENDPOINT_INVALID");
            }
            return bytes;
        }
    }

    void writePrivate(String name, JsonObject value, PetOfficeRuntimeLease lease) throws IOException {
        writeBytes(name, value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), lease);
    }

    void writeBytes(String name, byte[] bytes, PetOfficeRuntimeLease lease) throws IOException {
        java.util.Objects.requireNonNull(lease, "lease");
        synchronized (lease) {
            writePrivateOwned(name, bytes, lease);
        }
    }

    void removePrivate(String name, PetOfficeRuntimeLease lease) throws IOException {
        java.util.Objects.requireNonNull(lease, "lease");
        synchronized (lease) {
            if (!lease.isValid()) {
                throw new IOException("OFFICE_LEADER_LOST");
            }
            UserPrincipal owner = validateDirectory();
            Path file = privatePath(name);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                validateFile(file, owner);
                Files.delete(file);
            }
        }
    }

    /** A process owns its unique overlay marker, independently of the coordinator lease. */
    void writeMember(String name, JsonObject value) throws IOException {
        requireMemberName(name);
        writePrivateOwned(name, value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
    }

    void removeMember(String name) throws IOException {
        requireMemberName(name);
        UserPrincipal owner = validateDirectory();
        Path file = privatePath(name);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            validateFile(file, owner);
            Files.delete(file);
        }
    }

    private static void requireMemberName(String name) throws IOException {
        if (!name.matches("overlay-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.json")) {
            throw new IOException("OFFICE_PARTICIPANT_INVALID");
        }
    }

    private void writePrivateOwned(String name, byte[] bytes, PetOfficeRuntimeLease lease) throws IOException {
        if (lease != null && !lease.isValid()) {
            throw new IOException("OFFICE_LEADER_LOST");
        }
        UserPrincipal owner = validateDirectory();
        Path file = privatePath(name);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            validateFile(file, owner);
        }
        Path temporary = Files.createTempFile(directory, "coordinator-", ".json");
        try {
            // Only a newly created file may have its owner adjusted for an elevated Windows token.
            Files.setOwner(temporary, owner);
            AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) {
                throw new IOException("OFFICE_RUNTIME_ACL_UNAVAILABLE");
            }
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            if (bytes.length == 0 || bytes.length > limit(name)) {
                throw new IOException("OFFICE_ENDPOINT_INVALID");
            }
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            if (lease != null && !lease.isValid()) {
                throw new IOException("OFFICE_LEADER_LOST");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path privatePath(String name) throws IOException {
        if (!limits.containsKey(name)) {
            throw new IOException("OFFICE_FILE_INVALID");
        }
        return directory.resolve(name);
    }

    private int limit(String name) throws IOException {
        privatePath(name);
        return limits.get(name);
    }

    UserPrincipal validateDirectory() throws IOException {
        for (Path path = directory; path != null; path = path.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("OFFICE_RUNTIME_PATH_UNSAFE");
            }
        }
        UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
        validateAcl(directory, owner);
        return owner;
    }

    private static void validateFile(Path file, UserPrincipal owner) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || !Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).equals(owner)) {
            throw new IOException("OFFICE_ENDPOINT_UNSAFE");
        }
        validateAcl(file, owner);
    }

    private static void validateAcl(Path path, UserPrincipal owner) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("OFFICE_RUNTIME_ACL_UNAVAILABLE");
        }
        List<AclEntry> entries = view.getAcl();
        if (entries.stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner))
                || entries.stream().noneMatch(entry -> entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner)
                && entry.permissions().contains(AclEntryPermission.READ_DATA))) {
            throw new IOException("OFFICE_ENDPOINT_ACL_UNSAFE");
        }
    }

}
