package com.github.claudecodegui.pet.office;

import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class PetOfficeIpcConcurrencyTest {
    @Test
    public void concurrentPublishHeartbeatAndShowShareOneCorrelatedStream() throws Exception {
        var registry = new PetOfficeParticipantRegistry();
        var shown = new AtomicInteger();
        var workers = Executors.newFixedThreadPool(4);
        try (var server = new PetOfficeIpcServer("owner", registry, () -> true, shown::incrementAndGet);
             var client = new PetOfficeIpcClient(server.endpoint(), "idea")) {
            var calls = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 40; i++) {
                final int watermark = i + 1;
                calls.add(() -> {
                    client.publish(PetOfficeParticipantFixture.snapshot("idea", 64, watermark, watermark));
                    client.heartbeat();
                    client.show();
                    return null;
                });
            }
            for (var result : workers.invokeAll(calls, 15, TimeUnit.SECONDS)) {
                assertFalse(result.isCancelled());
                result.get();
            }
            assertEquals(40, shown.get());
            assertEquals(64, registry.view("owner", server.endpoint().leaderEpoch()).snapshot().sources().size());
            assertFalse(client.isClosed());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
