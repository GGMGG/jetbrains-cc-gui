package com.github.claudecodegui.clawbot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class ClawBotRuntimeFilesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void secretIsStablePrivateAndUnsafeOrOversizedReplacementsArePreserved() throws Exception {
        var root = temporary.getRoot().toPath().resolve("runtime");
        var secrets = new ClawBotIpcSecretStore(root);
        String first = secrets.loadOrCreate();
        assertEquals(first, secrets.loadOrCreate()); assertEquals(43, first.length());
        var files = new ClawBotRuntimeFiles(root);
        files.validateFile(root.resolve("gateway.auth"));
        assertThrows(IOException.class, () -> files.path("../outside"));
        byte[] oversized = new byte[513]; Files.write(root.resolve("gateway.auth"), oversized);
        assertThrows(IOException.class, secrets::loadOrCreate);
        assertArrayEquals(oversized, Files.readAllBytes(root.resolve("gateway.auth")));
    }

    @Test
    public void corruptDiscoverySurvivesLeaseCleanupAndTheOsLockIsStillReleased() throws Exception {
        var root = temporary.getRoot().toPath().resolve("runtime");
        var coordinator = new ClawBotProcessCoordinator(root, "idea");
        var lease = coordinator.tryAcquireLeader("loopback://127.0.0.1:1234"); assertNotNull(lease);
        byte[] corrupt = "{invalid".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("leader.json"), corrupt);
        assertThrows(IOException.class, lease::close);
        assertArrayEquals(corrupt, Files.readAllBytes(root.resolve("leader.json")));
        try (var successor = new ClawBotProcessCoordinator(root, "pycharm").tryAcquireLeader("loopback://127.0.0.1:4321")) {
            assertNotNull(successor);
            assertThrows(IOException.class, () -> lease.updateEndpoint("loopback://127.0.0.1:9999"));
        }
    }
}
