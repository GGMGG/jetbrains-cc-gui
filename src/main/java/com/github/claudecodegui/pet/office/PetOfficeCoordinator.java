package com.github.claudecodegui.pet.office;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Testable desktop ownership; closing transport never releases the caller's window lease. */
record PetOfficeCoordinator(PetOfficeRuntimeLease lease, PetOfficeEndpointStore store, PetOfficeIpcServer server,
                            PetOfficeParticipantRegistry registry, String instanceId, PetOfficeRecoveryStore recovery,
                            PetOfficeModeStore modes, PetOfficeModeStore.Control entering,
                            PetOfficeRemoteAppearances appearances) implements AutoCloseable {
    static PetOfficeCoordinator start(Path root, PetOfficeRuntimeLease lease, String instanceId, BooleanSupplier active,
                                      Runnable show, Runnable exit, PetOfficeRecoveryStore.Intent expected, boolean explicit) throws IOException {
        if (!active.getAsBoolean() || !lease.isValid()) {
            throw new IOException("OFFICE_LEADER_LOST");
        }
        var registry = new PetOfficeParticipantRegistry();
        var store = new PetOfficeEndpointStore(root);
        var server = new PetOfficeIpcServer(instanceId, registry, () -> active.getAsBoolean() && lease.isValid(), show);
        server.onExit(exit);
        var recovery = new PetOfficeRecoveryStore(root);
        var modes = new PetOfficeModeStore(root);
        var appearances = new PetOfficeRemoteAppearances(instanceId, registry,
                () -> active.getAsBoolean() && lease.isValid() && server.isRunning());
        boolean started = false;
        try {
            var entering = modes.beginEnter(server.endpoint().leaderEpoch(), lease);
            store.publish(server.endpoint(), lease);
            synchronized (lease) {
                if (!active.getAsBoolean() || !lease.isValid()) {
                    throw new IOException("OFFICE_LEADER_LOST");
                }
                recovery.adopt(server.endpoint().leaderEpoch(), expected, explicit, lease);
            }
            var coordinator = new PetOfficeCoordinator(lease, store, server, registry, instanceId, recovery, modes, entering, appearances);
            started = true;
            return coordinator;
        } finally {
            if (!started) {
                appearances.close();
                server.close();
                store.removeOwned(server.endpoint(), lease);
            }
        }
    }

    boolean owns() { return lease.isValid() && server.isRunning(); }

    void awaitDisplayReady(BooleanSupplier current) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (current.getAsBoolean() && owns() && System.nanoTime() - deadline < 0) {
            if (modes.commitOffice(entering, lease)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IOException("OFFICE_OVERLAY_DETACH_PENDING");
    }

    void presentation(boolean enabled, boolean visible) throws IOException {
        if (!owns()) {
            throw new IOException("OFFICE_LEADER_LOST");
        }
        recovery.write(server.endpoint().leaderEpoch(), enabled, visible, lease);
        if (!enabled) {
            modes.beginExit(server.endpoint().leaderEpoch(), lease);
        }
    }

    /** Called after native window/browser disposal; explicit exit may now restore overlays. */
    @Override
    public void close() throws IOException {
        appearances.close();
        server.close();
        var intent = recovery.read();
        if (intent != null && !intent.enabled()) {
            modes.commitOverlay(lease);
        }
        store.removeOwned(server.endpoint(), lease);
    }
}
