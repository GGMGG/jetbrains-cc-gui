package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Real child processes exercise blocked pipes and lifecycle races without provider access. */
public class ClawBotIlinkProcessTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final String READY = "process.stdout.write(JSON.stringify({type:'daemon',event:'ready'})+'\\n');\n";
    private static final String INPUT = "require('readline').createInterface({input:process.stdin}).on('line',line=>{const r=JSON.parse(line);\n";
    private static final String KEEP_ALIVE = "setInterval(()=>{},1000);\n";

    @Test public void concurrentResponsesAreCorrelatedAcrossReverseCompletion() throws Exception {
        try (var fixture = fixture(READY + "const requests=[];\n" + INPUT
                + "requests.push(r); if(requests.length===2) for(const v of requests.reverse()) "
                + "process.stdout.write(JSON.stringify({id:v.id,result:{method:v.method}})+'\\n');\n});\n", 3000)) {
            fixture.bridge.start();
            var workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> fixture.bridge.request("first", null));
                var second = workers.submit(() -> fixture.bridge.request("second", null));
                assertEquals("first", first.get(5, TimeUnit.SECONDS).get("method").getAsString());
                assertEquals("second", second.get(5, TimeUnit.SECONDS).get("method").getAsString());
            } finally { workers.shutdownNow(); }
        }
    }

    @Test public void providerTextIsDiscardedAndOnlyNumericErrorDetailsAreExposed() throws Exception {
        try (var fixture = fixture(READY + INPUT
                + "const error=r.method==='numeric'?{code:'ILINK_HTTP_FAILED',detail:'http=429;retryAfterMs=250'}:"
                + "{code:'untrusted provider error',detail:'sensitive fixture text'};"
                + "process.stdout.write(JSON.stringify({id:r.id,error})+'\\n');\n});\n", 3000)) {
            fixture.bridge.start();
            IOException redacted = failure(() -> fixture.bridge.request("redacted", null));
            assertEquals("CLAWBOT_ILINK_REQUEST_FAILED", redacted.getMessage());
            assertNull(((ClawBotIlinkProcess.IlinkDaemonException) redacted.getCause()).detail());
            IOException numeric = failure(() -> fixture.bridge.request("numeric", null));
            assertEquals("ILINK_HTTP_FAILED", numeric.getMessage());
            assertEquals("http=429;retryAfterMs=250", ((ClawBotIlinkProcess.IlinkDaemonException) numeric.getCause()).detail());
        }
    }

    @Test public void closeInterruptsPendingRequestAndDrainsLargeStderr() throws Exception {
        try (var fixture = fixture(READY + "process.stderr.write('x'.repeat(300000));\n" + INPUT
                + "require('fs').writeFileSync(require('path').join(__dirname,'received'),'ready');\n});\n", 5000)) {
            fixture.bridge.start();
            var worker = Executors.newSingleThreadExecutor();
            try {
                var pending = worker.submit(() -> failure(() -> fixture.bridge.request("status", null)));
                awaitMarker(fixture.script.getParent().resolve("received"));
                long before = System.nanoTime();
                fixture.bridge.close();
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500);
                assertNotNull(pending.get(2, TimeUnit.SECONDS));
                assertFalse(fixture.bridge.isRunning());
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void closeDoesNotWaitForStartupAcknowledgement() throws Exception {
        try (var fixture = fixture(KEEP_ALIVE, 5000)) {
            var worker = Executors.newSingleThreadExecutor();
            try {
                var starting = worker.submit(() -> failure(fixture.bridge::start));
                assertTrue(fixture.launched.await(3, TimeUnit.SECONDS));
                long before = System.nanoTime();
                fixture.bridge.close();
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500);
                assertNotNull(starting.get(2, TimeUnit.SECONDS));
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void deadlineIncludesAWriteBlockedByAnUnreadChildPipe() throws Exception {
        try (var fixture = fixture(READY + "process.stdin.pause();\n" + KEEP_ALIVE, 500)) {
            fixture.bridge.start();
            JsonObject params = new JsonObject();
            params.addProperty("text", "x".repeat(220000));
            var worker = Executors.newSingleThreadExecutor();
            try {
                long before = System.nanoTime();
                var blocked = worker.submit(() -> failure(() -> fixture.bridge.request("send_text", params)));
                assertNotNull(blocked.get(3, TimeUnit.SECONDS));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2500);
                assertFalse(fixture.bridge.isRunning());
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void oversizedUnterminatedResponseFailsWithinTheDeadline() throws Exception {
        try (var fixture = fixture(READY + INPUT + "process.stdout.write('x'.repeat(300000));\n});\n", 3000)) {
            fixture.bridge.start();
            long before = System.nanoTime();
            assertNotNull(failure(() -> fixture.bridge.request("status", null)));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2000);
            assertFalse(fixture.bridge.isRunning());
        }
    }

    @Test public void oversizedOutgoingRequestDoesNotPoisonTheNextRequest() throws Exception {
        try (var fixture = fixture(READY + INPUT + "process.stdout.write(JSON.stringify({id:r.id,result:{ok:true}})+'\\n');\n});\n", 3000)) {
            fixture.bridge.start();
            JsonObject oversized = new JsonObject();
            oversized.addProperty("text", "x".repeat(300000));
            assertEquals("CLAWBOT_ILINK_REQUEST_INVALID", failure(() -> fixture.bridge.request("send_text", oversized)).getMessage());
            assertTrue(fixture.bridge.request("status", null).get("ok").getAsBoolean());
        }
    }

    private Fixture fixture(String source, long requestTimeout) throws IOException {
        Path script = temporary.newFolder().toPath().resolve("fixture.cjs");
        Files.writeString(script, source, StandardCharsets.UTF_8);
        return new Fixture(script, requestTimeout);
    }

    private static void awaitMarker(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.exists(path) && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertTrue(Files.exists(path));
    }

    private static IOException failure(Checked operation) throws Exception {
        try { operation.run(); } catch (IOException expected) { return expected; }
        fail("Expected a bounded protocol failure");
        throw new AssertionError();
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private static final class Fixture implements AutoCloseable {
        private final Path script;
        private final AtomicReference<Process> child = new AtomicReference<>();
        private final CountDownLatch launched = new CountDownLatch(1);
        private final ClawBotIlinkProcess bridge;

        private Fixture(Path script, long requestTimeout) {
            this.script = script;
            bridge = new ClawBotIlinkProcess(() -> {
                Process started = new ProcessBuilder("node", script.toString()).start();
                child.set(started);
                launched.countDown();
                return started;
            }, 5000, requestTimeout);
        }

        @Override public void close() throws InterruptedException {
            bridge.close();
            Process current = child.get();
            if (current != null) {
                current.destroyForcibly();
                assertTrue(current.waitFor(3, TimeUnit.SECONDS));
            }
        }
    }
}
