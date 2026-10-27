package com.github.claudecodegui.pet.office;

import com.github.claudecodegui.session.SessionState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PetOfficeLivePageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Gson JSON = new Gson();

    @Test public void scenesDoNotAccumulateWhileACompleteTransferAwaitsAcknowledgement() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.receive("ready", "protocolVersion", 1);
            fixture.drain();
            int count = fixture.packets.size();
            fixture.page.refresh();
            fixture.drain();
            assertEquals(count, fixture.packets.size());
            JsonObject begin = fixture.packets.stream().filter(p -> "SCENE_BEGIN".equals(text(p, "type"))).findFirst().orElseThrow();
            fixture.receive("sceneAck", "transferId", text(begin, "transferId"), "sequence", begin.get("sequence").getAsLong());
            fixture.page.refresh();
            fixture.drain();
            assertTrue(fixture.packets.size() > count);
        }
    }

    @Test public void storagePreferencesReplyOnlyAfterTheWindowAcknowledgement() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ready();
            var before = fixture.store.read().state();
            JsonObject prefs = JSON.toJsonTree(before.preferences()).getAsJsonObject();
            prefs.addProperty("officeName", "Acknowledged office");
            prefs.addProperty("topmost", !before.preferences().topmost());
            fixture.preferences(before.revision(), prefs);
            assertEquals("Acknowledged office", fixture.store.read().state().preferences().officeName());
            assertEquals(0, fixture.applied.get());
            assertTrue(fixture.packets.isEmpty());
            fixture.drain();
            assertEquals(1, fixture.applied.get());
            assertTrue(fixture.packets.stream().anyMatch(p -> p.has("state") && "value".equals(text(p, "type"))));
        }
    }

    @Test public void failedNativeWindowApplicationRollsBackAllSharedPreferences() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ready();
            var before = fixture.store.read().state();
            JsonObject prefs = JSON.toJsonTree(before.preferences()).getAsJsonObject();
            prefs.addProperty("officeName", "Rejected office");
            prefs.addProperty("showIdle", !before.preferences().showIdle());
            fixture.windowFails.set(true);
            fixture.preferences(before.revision(), prefs);
            fixture.drain();
            assertEquals(before.preferences(), fixture.store.read().state().preferences());
            assertTrue(fixture.packets.stream().anyMatch(p -> "OFFICE_WINDOW_UPDATE_FAILED".equals(text(p, "code"))));
        }
    }

    @Test public void retiredPageCannotPublishQueuedTransfersOrWritePreferences() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.receive("ready", "protocolVersion", 1);
            fixture.active.set(false);
            fixture.drain();
            assertTrue(fixture.packets.isEmpty());
            assertThrows(IOException.class, () -> fixture.receive("resync"));
            fixture.page.close();
            fixture.page.refresh();
            fixture.drain();
            assertTrue(fixture.packets.isEmpty());
        }
    }

    @Test public void artCapacityIsHeldUntilTheEdtTransferFinishes() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ready();
            JsonObject begin = fixture.lastBegin;
            fixture.receive("sceneAck", "transferId", text(begin, "transferId"), "sequence", begin.get("sequence").getAsLong());
            fixture.asset();
            fixture.asset();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (fixture.artReads.get() < 2 && System.nanoTime() < deadline) { Thread.sleep(10); }
            assertEquals(2, fixture.artReads.get());
            fixture.asset();
            fixture.drain();
            assertTrue(fixture.packets.stream().anyMatch(p -> "ASSET_BUSY".equals(text(p, "result"))));
            assertFalse(fixture.packets.isEmpty());
        }
    }

    private static String text(JsonObject value, String name) { return value.has(name) ? value.get(name).getAsString() : ""; }

    private final class Fixture implements AutoCloseable, PetOfficeLivePage.Sources {
        private final Path root = PetOfficeTestRoot.create(temporary);
        private final PetOfficeRuntimeLease lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow();
        private final String token = UUID.randomUUID().toString();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean windowFails = new AtomicBoolean();
        private final AtomicInteger applied = new AtomicInteger();
        private final AtomicInteger artReads = new AtomicInteger();
        private final ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        private final List<JsonObject> packets = new ArrayList<>();
        private final PetOfficePreferencesStore store = new PetOfficePreferencesStore(root);
        private final PetOfficeSessionRegistry registry = new PetOfficeSessionRegistry("owner");
        private final byte[] artBytes = {1, 2, 3};
        private final PetOfficeAppearanceService.Appearance appearance;
        private final PetOfficeLivePage page;
        private JsonObject lastBegin;

        private Fixture() throws Exception {
            registry.register(new PetOfficeSessionRegistry.Metadata("tab", "IC", "IDEA", "project", "Project", "Tab"), new SessionState());
            var service = new PetOfficeAppearanceService(root, () -> null);
            var builtin = service.resolve(null);
            appearance = new PetOfficeAppearanceService.Appearance(PetOfficeDigests.sha256(new byte[]{4}), "global", 1,
                    builtin.actionMappings(), "READY", new PetOfficeAppearanceService.Manifest(PetOfficeDigests.sha256(artBytes), "image", "image/png", 1, 1, artBytes.length));
            page = new PetOfficeLivePage(token, root, lease, this, active::get,
                    new PetOfficeWindowPreferenceController(store, lease, store.read()), next -> {
                        applied.incrementAndGet();
                        if (windowFails.get()) { throw new IllegalStateException("Fixture native failure"); }
                    }, service, ui::add, Runnable::run, (callback, json) -> {
                        if (callback.equals("petOfficeReceive") || callback.equals("petOfficeStorageResult")) { packets.add(JsonParser.parseString(json).getAsJsonObject()); }
                    });
        }

        private void ready() throws Exception {
            receive("ready", "protocolVersion", 1);
            drain();
            lastBegin = packets.stream().filter(p -> "SCENE_BEGIN".equals(text(p, "type"))).findFirst().orElseThrow();
            packets.clear();
        }
        private void preferences(String revision, JsonObject prefs) throws Exception {
            JsonObject payload = new JsonObject();
            payload.addProperty("expectedRevision", revision);
            payload.add("preferences", prefs);
            receive("storage", "requestId", UUID.randomUUID().toString(), "operation", "preferences", "payload", payload.toString());
        }
        private void asset() throws Exception {
            receive("asset", "requestId", UUID.randomUUID().toString(), "sourceKey", PetOfficeSessionRegistry.key(registry.snapshot().sources().get(0).sourceKey()),
                    "appearanceRevision", appearance.revision(), "assetDigest", appearance.manifest().digest());
        }
        private void receive(String command, Object... fields) throws Exception {
            JsonObject message = new JsonObject();
            message.addProperty("pageGeneration", token);
            message.addProperty("command", command);
            for (int index = 0; index < fields.length; index += 2) { message.add((String) fields[index], JSON.toJsonTree(fields[index + 1])); }
            page.receive(PetOfficeLiveMessage.parse(message.toString()));
        }
        private void drain() {
            Runnable action;
            int count = 0;
            while ((action = ui.poll()) != null) { assertTrue(++count < 1000); action.run(); }
        }
        public PetOfficeParticipantRegistry.View view() { return new PetOfficeParticipantRegistry.View(registry.snapshot(), List.of("owner")); }
        public Map<String, PetOfficeAppearanceService.Appearance> appearances(PetOfficeSessionRegistry.CompleteSnapshot snapshot) {
            return Map.of(PetOfficeSessionRegistry.key(snapshot.sources().get(0).sourceKey()), appearance);
        }
        public byte[] bytes(String key, PetOfficeAppearanceService.Appearance ignored, BooleanSupplier allowed) {
            artReads.incrementAndGet();
            return allowed.getAsBoolean() ? artBytes : null;
        }
        @Override public void close() throws IOException { active.set(false); page.close(); lease.close(); }
    }
}
