package com.github.claudecodegui.provider.common;

import com.github.claudecodegui.bridge.AiDataProcessGate;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AiDataDaemonQuiescenceTest {
    private static final DaemonBridge.DaemonOutputCallback CALLBACK = new DaemonBridge.DaemonOutputCallback() {
        @Override
        public void onLine(String line) { }

        @Override
        public void onStderr(String text) { }

        @Override
        public void onError(String error) { }

        @Override
        public void onComplete(boolean success) { }
    };

    @Test
    public void idlePrewarmedDaemonsFromTwoOwnersExitBeforeMigration() {
        AiDataProcessGate gate = new AiDataProcessGate();
        AtomicReference<Process> firstProcess = new AtomicReference<>();
        AtomicReference<Process> secondProcess = new AtomicReference<>();
        DaemonBridge first = daemon(gate, firstProcess);
        DaemonBridge second = daemon(gate, secondProcess);
        try {
            assertTrue(first.start());
            assertTrue(second.start());
            try (AiDataProcessGate.MigrationPermit migration = gate.tryAcquireQuiescentMigrationPermit()) {
                assertNotNull(migration);
                assertFalse(firstProcess.get().isAlive());
                assertFalse(secondProcess.get().isAlive());
            }
        } finally {
            first.stop();
            second.stop();
        }
    }

    @Test
    public void activeDaemonRequestRejectsMigrationAndKeepsItsProcessAlive() throws Exception {
        AiDataProcessGate gate = new AiDataProcessGate();
        AtomicReference<Process> process = new AtomicReference<>();
        DaemonBridge daemon = daemon(gate, process);
        try {
            CompletableFuture<Boolean> request = daemon.sendCommand("hold", new JsonObject(), CALLBACK);
            assertFalse(request.isDone());
            assertTrue(daemon.hasActiveWork());
            assertNull(gate.tryAcquireQuiescentMigrationPermit());
            assertTrue(process.get().isAlive());
            assertFalse(request.isDone());
        } finally {
            daemon.stop();
        }
    }

    @Test
    public void newSendAfterQuiescenceWaitsForPrecommitRollbackThenRestarts() throws Exception {
        AiDataProcessGate gate = new AiDataProcessGate();
        AtomicReference<Process> process = new AtomicReference<>();
        DaemonBridge daemon = daemon(gate, process);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AiDataProcessGate.MigrationPermit migration = null;
        try {
            assertTrue(daemon.start());
            Process retired = process.get();
            migration = gate.tryAcquireQuiescentMigrationPermit();
            assertNotNull(migration);
            CompletableFuture<Boolean> send = CompletableFuture.supplyAsync(
                    () -> daemon.sendCommand("echo", new JsonObject(), CALLBACK), executor).thenCompose(future -> future);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!migration.isCancellationRequested() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(migration.isCancellationRequested());
            assertFalse(send.isDone());
            migration.close();
            assertTrue(send.get(10, TimeUnit.SECONDS));
            assertNotSame(retired, process.get());
            assertFalse(retired.isAlive());
        } finally {
            if (migration != null) {
                migration.close();
            }
            daemon.stop();
            executor.shutdownNow();
        }
    }

    private static DaemonBridge daemon(AiDataProcessGate gate, AtomicReference<Process> process) {
        return new DaemonBridge(null, null, null, DaemonBridge.TimeSource.system(), () -> {
            String script = "process.stdout.write(JSON.stringify({type:'daemon',event:'ready'})+'\\n');"
                    + "require('node:readline').createInterface({input:process.stdin}).on('line',line=>{"
                    + "const request=JSON.parse(line);if(request.method==='shutdown'){process.exit(0);}"
                    + "if(request.method!=='hold'){process.stdout.write(JSON.stringify({id:request.id,done:true,success:true})+'\\n');}});";
            ProcessBuilder builder = new ProcessBuilder("node", "-e", script);
            Map<String, String> environment = builder.environment();
            environment.keySet().removeIf(name -> name.matches("(?i).*(API_KEY|TOKEN|SECRET|AUTHORIZATION|NODE_OPTIONS).*"));
            Process started = builder.start();
            process.set(started);
            return started;
        }, null, 60_000L, null, gate);
    }
}
