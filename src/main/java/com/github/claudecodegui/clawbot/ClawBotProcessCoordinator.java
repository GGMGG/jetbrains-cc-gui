package com.github.claudecodegui.clawbot;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Coordinates the single Claw Bot gateway leader for one desktop runtime. */
public final class ClawBotProcessCoordinator {

    static final int PROTOCOL_VERSION = 1;
    private static final String LOCK_FILE = "gateway.lock";
    private static final String LEADER_FILE = "leader.json";
    private static final long MAX_LEADER_FILE_BYTES = 4096L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.util.Set<Path> LOCAL_LEADERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Path runtimeDirectory;
    private final String instanceId;
    private final ClawBotRuntimeFiles files;

    public ClawBotProcessCoordinator(Path runtimeDirectory, String instanceId) {
        this.runtimeDirectory = runtimeDirectory.toAbsolutePath().normalize();
        this.instanceId = requireValue(instanceId, "instanceId", 256);
        this.files = new ClawBotRuntimeFiles(this.runtimeDirectory);
    }

    /** Creates a coordinator with a process-unique instance identity. */
    public ClawBotProcessCoordinator(Path runtimeDirectory) {
        this(runtimeDirectory, UUID.randomUUID().toString());
    }

    /** Returns the per-user runtime root used by the first implementation. */
    public static Path defaultRuntimeDirectory() {
        String localAppData = PlatformUtils.getEnvIgnoreCase("LOCALAPPDATA");
        Path root = localAppData == null || localAppData.isBlank()
                ? Path.of(PlatformUtils.getHomeDirectory()).resolve(".cc-gui")
                : Path.of(localAppData).resolve("CC GUI");
        return root.resolve("ClawBot").resolve("runtime");
    }

    /** Tries to become leader without waiting for another process. */
    public LeaderLease tryAcquireLeader(String endpoint) throws IOException {
        String safeEndpoint = requireValue(endpoint, "endpoint", 512);
        files.prepare();
        if (Files.exists(lockPath(), LinkOption.NOFOLLOW_LINKS)) {
            files.validateFile(lockPath());
        }
        Path lockIdentity = runtimeDirectory.toRealPath().resolve(LOCK_FILE);
        if (!LOCAL_LEADERS.add(lockIdentity)) {
            return null;
        }
        FileChannel channel = null;
        FileLock lock = null;
        boolean acquired = false;
        try {
            channel = FileChannel.open(lockPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            ClawBotRuntimeFiles.restrict(lockPath(), false);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ignored) {
                closeQuietly(channel);
                return null;
            }
            if (lock == null) {
                closeQuietly(channel);
                return null;
            }
            writeLeader(safeEndpoint);
            LeaderLease lease = new LeaderLease(this, channel, lock, instanceId, leaderPath(), lockIdentity);
            acquired = true;
            return lease;
        } catch (IOException | RuntimeException error) {
            try {
                if (lock != null) {
                    lock.release();
                }
            } catch (IOException releaseError) {
                error.addSuppressed(releaseError);
            }
            closeQuietly(channel);
            throw error;
        } finally {
            if (!acquired) {
                LOCAL_LEADERS.remove(lockIdentity);
            }
        }
    }

    /** Reads the last owner record without treating it as lock authority. */
    public Optional<LeaderInfo> readLeader() throws IOException {
        byte[] bytes = files.read(LEADER_FILE, (int) MAX_LEADER_FILE_BYTES);
        if (bytes == null) {
            return Optional.empty();
        }
        try {
            JsonElement value = ClawBotIpcJson.parse(bytes);
            if (!value.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject object = value.getAsJsonObject();
            if (!object.has("protocolVersion")
                    || Math.toIntExact(ClawBotJsonNumbers.nonNegativeLong(object, "protocolVersion")) != PROTOCOL_VERSION) {
                return Optional.empty();
            }
            return Optional.of(new LeaderInfo(
                    Math.toIntExact(ClawBotJsonNumbers.nonNegativeLong(object, "protocolVersion")),
                    object.get("instanceId").getAsString(),
                    object.get("endpoint").getAsString(),
                    ClawBotJsonNumbers.nonNegativeLong(object, "pid"),
                    object.get("startedAt").getAsString(),
                    object.has("processStartedAt") ? object.get("processStartedAt").getAsString() : null));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    private void writeLeader(String endpoint) throws IOException {
        JsonObject owner = new JsonObject();
        owner.addProperty("protocolVersion", PROTOCOL_VERSION);
        owner.addProperty("instanceId", instanceId);
        owner.addProperty("endpoint", endpoint);
        owner.addProperty("pid", ProcessHandle.current().pid());
        owner.addProperty("startedAt", Instant.now().toString());
        ProcessHandle.current().info().startInstant().ifPresent(
                value -> owner.addProperty("processStartedAt", value.toString()));
        files.write(LEADER_FILE, GSON.toJson(owner).getBytes(StandardCharsets.UTF_8));
    }

    private Path lockPath() {
        return runtimeDirectory.resolve(LOCK_FILE);
    }

    private Path leaderPath() {
        return runtimeDirectory.resolve(LEADER_FILE);
    }

    private static String requireValue(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    /** Immutable owner metadata shown to a blocked follower. */
    public record LeaderInfo(
            int protocolVersion,
            String instanceId,
            String endpoint,
            long pid,
            String startedAt,
            String processStartedAt
    ) {
    }

    /** Holds the OS lock until the gateway stops or loses leadership. */
    public static final class LeaderLease implements AutoCloseable {
        private final ClawBotProcessCoordinator coordinator;
        private final FileChannel channel;
        private final FileLock lock;
        private final String ownerId;
        private final Path leaderPath;
        private final Path lockIdentity;
        private boolean closed;

        private LeaderLease(
                ClawBotProcessCoordinator coordinator,
                FileChannel channel,
                FileLock lock,
                String ownerId,
                Path leaderPath,
                Path lockIdentity
        ) {
            this.coordinator = coordinator;
            this.channel = channel;
            this.lock = lock;
            this.ownerId = ownerId;
            this.leaderPath = leaderPath;
            this.lockIdentity = lockIdentity;
        }

        /** Replaces the temporary endpoint after the authenticated server is bound. */
        public synchronized void updateEndpoint(String endpoint) throws IOException {
            String safeEndpoint = requireValue(endpoint, "endpoint", 512);
            if (closed || !lock.isValid() || !channel.isOpen()) {
                throw new IOException("CLAWBOT_LEADER_CLOSED");
            }
            if (!ownsLeaderRecord()) {
                throw new IOException("CLAWBOT_LEADER_LOST");
            }
            coordinator.writeLeader(safeEndpoint);
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                deleteLeaderIfOwned();
            } finally {
                try {
                    lock.release();
                } finally {
                    try {
                        channel.close();
                    } finally {
                        LOCAL_LEADERS.remove(lockIdentity);
                    }
                }
            }
        }

        private void deleteLeaderIfOwned() throws IOException {
            if (ownsLeaderRecord()) {
                coordinator.files.validateFile(leaderPath);
                Files.delete(leaderPath);
            }
        }

        private boolean ownsLeaderRecord() throws IOException {
            var current = coordinator.readLeader();
            return current.isPresent() && ownerId.equals(current.get().instanceId());
        }
    }
}
