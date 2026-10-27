package com.github.claudecodegui.pet.office;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Page-owned delivery, with separate bounded art workers and acknowledged native preference commits. */
final class PetOfficeLivePage implements AutoCloseable {
    interface Sources {
        PetOfficeParticipantRegistry.View view() throws IOException;
        Map<String, PetOfficeAppearanceService.Appearance> appearances(PetOfficeSessionRegistry.CompleteSnapshot snapshot) throws IOException;
        byte[] bytes(String key, PetOfficeAppearanceService.Appearance appearance, BooleanSupplier allowed) throws IOException;
    }
    private static final Gson JSON = new Gson();
    private final String page;
    private final Sources sources;
    private final BooleanSupplier active;
    private final Consumer<Runnable> ui;
    private final Consumer<Runnable> io;
    private final BiConsumer<String, String> publish;
    private final PetOfficeWindowPreferenceController preferences;
    private final PetOfficeWindowPreferenceController.WindowTarget window;
    private final PetOfficeAppearanceService appearances;
    private final PetOfficeSceneDelivery delivery;
    private final PetOfficeSharedStorage storage;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicInteger storageRequests = new AtomicInteger();
    private final AtomicInteger assetRequests = new AtomicInteger();
    private final ThreadPoolExecutor art = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
            task -> PetOfficeIpcServer.daemon(task, "Pet Office page art"));
    private volatile boolean ready;
    private volatile long resyncGeneration;

    PetOfficeLivePage(String page, Path directory, PetOfficeRuntimeLease lease, Sources sources, BooleanSupplier active,
                      PetOfficeWindowPreferenceController preferences, PetOfficeWindowPreferenceController.WindowTarget window,
                      PetOfficeAppearanceService appearances, Consumer<Runnable> ui, Consumer<Runnable> io, BiConsumer<String, String> publish) {
        this.page = page;
        this.sources = sources;
        this.active = active;
        this.preferences = preferences;
        this.window = window;
        this.appearances = appearances;
        this.ui = ui;
        this.io = io;
        this.publish = publish;
        delivery = new PetOfficeSceneDelivery(page);
        storage = new PetOfficeSharedStorage(directory, lease, this::current, appearances::choices);
    }

    private boolean current() { return !closed.get() && active.getAsBoolean(); }

    void receive(PetOfficeLiveMessage message) throws IOException {
        if (!current() || !page.equals(message.pageGeneration())) { throw new IOException("OFFICE_PAGE_EXPIRED"); }
        switch (message.command()) {
            case "ready":
                if (message.protocolVersion() != PetOfficeSceneProtocol.VERSION) { throw new IOException("OFFICE_PROTOCOL_UNSUPPORTED"); }
                ready = true;
                refresh();
                break;
            case "sceneAck":
                delivery.acceptAck(message.field("transferId"), message.sequence());
                break;
            case "resync":
                resyncGeneration++;
                delivery.requestResync();
                refresh();
                break;
            case "storage":
                storage(message);
                break;
            case "asset":
                asset(message);
                break;
            case "metrics":
                break;
            default:
                throw new IOException("OFFICE_REQUEST_UNSUPPORTED");
        }
    }

    void refresh() {
        if (!ready || !current() || !delivery.canSend() || !refreshing.compareAndSet(false, true)) { return; }
        try {
            preferences.refresh();
            appearances.beginSnapshot();
            var captured = sources.appearances(sources.view().snapshot());
            var latest = sources.view();
            var overrides = preferences.state().preferences().appearanceOverrides();
            Map<String, PetOfficeAppearanceService.Appearance> admitted = new LinkedHashMap<>();
            for (var source : latest.snapshot().sources()) {
                String key = PetOfficeSessionRegistry.key(source.sourceKey());
                var visual = captured.get(key);
                if (overrides.containsKey(key)) { visual = appearances.resolveSession(visual, overrides.get(key), resyncGeneration); }
                admitted.put(key, visual == null ? appearances.resolve(null) : visual);
            }
            var transfer = delivery.prepare(new PetOfficeSceneProtocol.Scene(latest.snapshot(), admitted, latest.participants()));
            if (transfer != null && current()) {
                frames("petOfficeReceive", transfer.frames(), () -> delivery.isCurrent(transfer), () -> delivery.markSent(transfer), 0);
            }
        } catch (IOException | RuntimeException unavailable) {
            // Retain the last complete scene. A later bounded refresh can recover without inventing observations.
        } finally { refreshing.set(false); }
    }

    private void storage(PetOfficeLiveMessage message) {
        String id = message.field("requestId");
        if (storageRequests.incrementAndGet() > 2) {
            storageRequests.decrementAndGet();
            storageReply(List.of(storage.error(id, "OFFICE_STORAGE_BUSY")), () -> { });
            return;
        }
        Runnable finished = storageRequests::decrementAndGet;
        try {
            if (message.field("operation").equals("preferences")) {
                JsonObject value = PetOfficeIpcFrameCodec.parse(message.field("payload").getBytes(StandardCharsets.UTF_8));
                PetOfficeIpcFrameCodec.fields(value, "expectedRevision", "preferences");
                var requested = PetOfficePreferencesStore.preferences(value.getAsJsonObject("preferences"));
                preferences.updateFull(PetOfficeIpcFrameCodec.string(value, "expectedRevision", 64), requested, this::current, window, ui, io, result -> {
                    if (result.error() == null) {
                        JsonObject reply = new JsonObject();
                        reply.addProperty("requestId", id);
                        reply.addProperty("type", "value");
                        reply.add("state", JSON.toJsonTree(preferences.state()));
                        packet("petOfficePreferencesChanged", JSON.toJson(preferences.state()));
                        storageReply(List.of(reply), finished);
                    } else { storageReply(List.of(storage.error(id, result.error())), finished); }
                });
            } else { storageReply(storage.request(id, message.field("operation"), message.field("payload")), finished); }
        } catch (IOException | RuntimeException invalid) {
            String code = invalid.getMessage();
            storageReply(List.of(storage.error(id, code != null && code.matches("OFFICE_[A-Z_]{1,56}") ? code : "OFFICE_STORAGE_INVALID")), finished);
        }
    }

    private void storageReply(List<JsonObject> packets, Runnable finished) {
        var frames = packets.stream().map(packet -> {
            packet.addProperty("pageGeneration", page);
            return JSON.toJson(packet);
        }).toList();
        frames("petOfficeStorageResult", frames, () -> true, finished, 0);
    }

    private void asset(PetOfficeLiveMessage message) {
        String id = message.field("requestId");
        if (assetRequests.incrementAndGet() > 2) {
            assetRequests.decrementAndGet();
            result(id, "ASSET_BUSY");
            return;
        }
        try {
            art.execute(() -> {
                boolean handedOff = false;
                try {
                    String key = message.field("sourceKey");
                    var appearance = delivery.appearance(key, message.field("appearanceRevision"), message.field("assetDigest"));
                    if (!current() || appearance == null || !registered(key)) { throw new IOException("OFFICE_ASSET_STALE"); }
                    BooleanSupplier allowed = () -> current() && registered(key)
                            && delivery.appearance(key, appearance.revision(), appearance.manifest().digest()) != null;
                    byte[] bytes = "session".equals(appearance.scope()) ? appearances.bytes(appearance.manifest()) : sources.bytes(key, appearance, allowed);
                    if (bytes == null || !allowed.getAsBoolean()) { throw new IOException("OFFICE_ASSET_STALE"); }
                    var transfer = PetOfficeSceneProtocol.asset(page, id, key, appearance.revision(), appearance.manifest(), bytes);
                    frames("petOfficeReceive", transfer.frames(), allowed, assetRequests::decrementAndGet, 0);
                    handedOff = true;
                } catch (IOException | RuntimeException unavailable) { result(id, "ASSET_RETRY"); }
                finally { if (!handedOff) { assetRequests.decrementAndGet(); } }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            assetRequests.decrementAndGet();
            result(id, "ASSET_BUSY");
        }
    }

    private boolean registered(String key) {
        try { return sources.view().snapshot().sources().stream().anyMatch(source -> PetOfficeSessionRegistry.key(source.sourceKey()).equals(key)); }
        catch (IOException | RuntimeException unavailable) { return false; }
    }

    void result(String id, String result) { packet("petOfficeReceive", PetOfficeSceneProtocol.result(page, id, result)); }
    void visible(boolean visible) { if (ready) { packet("petOfficeSetVisible", Boolean.toString(visible)); } }

    private void packet(String callback, String json) {
        try { ui.accept(() -> { if (current()) { publish.accept(callback, json); } }); }
        catch (RuntimeException unavailable) { /* A retired page cannot receive another completion. */ }
    }

    private void frames(String callback, List<String> frames, BooleanSupplier allowed, Runnable finished, int start) {
        try {
            ui.accept(() -> {
                if (!current() || !ready || !allowed.getAsBoolean()) {
                    finished.run();
                    return;
                }
                try {
                    int end = Math.min(start + 8, frames.size());
                    for (int index = start; index < end; index++) { publish.accept(callback, frames.get(index)); }
                    if (end < frames.size()) { frames(callback, frames, allowed, finished, end); }
                    else { finished.run(); }
                } catch (RuntimeException | LinkageError unavailable) { finished.run(); }
            });
        } catch (RuntimeException unavailable) { finished.run(); }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            delivery.close();
            art.shutdownNow();
            storage.close();
        }
    }
}
