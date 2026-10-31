package com.github.claudecodegui.clawbot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClawBotProcessCoordinatorReviewTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void failedLocalContenderCannotReleaseTheLeadersOperatingSystemLock() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path runtime = root.resolve("runtime");
        ClawBotProcessCoordinator first = new ClawBotProcessCoordinator(runtime, "first");
        ClawBotProcessCoordinator second = new ClawBotProcessCoordinator(runtime, "second");
        try (var leader = first.tryAcquireLeader("pending")) {
            assertNotNull(leader);
            assertNull(second.tryAcquireLeader("pending"));
            assertEquals("LOCKED", probe(root, runtime.resolve("gateway.lock")));
        }
        assertEquals("AVAILABLE", probe(root, runtime.resolve("gateway.lock")));
        try (var replacement = second.tryAcquireLeader("pending")) {
            assertNotNull(replacement);
        }
    }

    private static String probe(Path root, Path lockPath) throws Exception {
        String className = LockProbe.class.getName();
        String resource = className.replace('.', '/') + ".class";
        Path fixture = root.resolve("probe-classes").resolve(resource);
        Files.createDirectories(fixture.getParent());
        try (var input = LockProbe.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            Files.write(fixture, input.readAllBytes());
        }
        Path output = root.resolve("probe-output.txt");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", root.resolve("probe-classes").toString(), className, lockPath.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().keySet().removeAll(java.util.Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        Process process = builder.start();
        try {
            assertTrue("The independent lock probe must finish", process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(Files.readString(output), 0, process.exitValue());
            return Files.readString(output).trim();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static final class LockProbe {
        public static void main(String[] arguments) throws Exception {
            try (var channel = FileChannel.open(Path.of(arguments[0]), StandardOpenOption.WRITE);
                    var lock = channel.tryLock()) {
                System.out.println(lock == null ? "LOCKED" : "AVAILABLE");
            }
        }
    }
}
