package com.github.claudecodegui.pet.office;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Worker-side durable mode sampling and EDT detachment acknowledgement. */
final class PetOfficeDisplayController {
    private static final Logger LOG = Logger.getInstance(PetOfficeDisplayController.class);
    private final ExecutorService worker;
    private final BooleanSupplier disposed;
    private final BooleanSupplier hasOwner;
    private final Supplier<PetOfficeSessionPublisher> publisher;
    private PetOfficeModeStore modes;
    private PetOfficeModeStore.Member member;
    private Path directory;
    private volatile PetOfficeModeStore.Control observedMode;
    private final AtomicBoolean modeUiQueued = new AtomicBoolean();

    PetOfficeDisplayController(ExecutorService worker, BooleanSupplier disposed, BooleanSupplier hasOwner, Supplier<PetOfficeSessionPublisher> publisher) {
        this.worker = worker;
        this.disposed = disposed;
        this.hasOwner = hasOwner;
        this.publisher = publisher;
    }

    void register(Path root) throws IOException {
        if (directory != null && !directory.equals(root)) {
            throw new IOException("OFFICE_NAMESPACE_CHANGED");
        }
        directory = root;
        ensureModeMember();
    }

    void observe(Path root) throws IOException {
        register(root);
        pollMode();
    }

    void failed() { detachAfterModeFailure(); }

    private void ensureModeMember() throws IOException {
        if (modes == null) {
            modes = new PetOfficeModeStore(directory);
        }
        if (member == null) {
            ProcessHandle process = ProcessHandle.current();
            long started = process.info().startInstant().orElseThrow(() -> new IOException("OFFICE_PROCESS_IDENTITY_UNAVAILABLE")).toEpochMilli();
            member = new PetOfficeModeStore.Member(publisher.get().snapshot().instanceId(), process.pid(), started, "");
            modes.register(member);
        }
    }

    private void pollMode() throws IOException {
        PetOfficeModeStore.Control mode = modes.read();
        if (mode.mode() == PetOfficeModeStore.Mode.EXITING_OFFICE && !hasOwner.getAsBoolean()) {
            var intent = new PetOfficeRecoveryStore(directory).read();
            if (intent != null && !intent.enabled()) {
                var available = PetOfficeRuntimeLease.tryAcquire(directory);
                if (available.isPresent()) {
                    try (var administrative = available.get()) {
                        if (modes.read().equals(mode)) {
                            modes.commitOverlay(administrative);
                        }
                    }
                    mode = modes.read();
                }
            }
        }
        PetOfficeModeStore.Control observed = mode;
        observedMode = observed;
        boolean allow = observed.mode() == PetOfficeModeStore.Mode.IDE_OVERLAY;
        if (!allow) {
            com.github.claudecodegui.pet.CodexPetFloatingService.revokeOfficeOverlayPermission();
        }
        if (member.acknowledgedEpoch().equals(observed.epoch())
                && com.github.claudecodegui.pet.CodexPetFloatingService.officeOverlayPermitted() == allow || !modeUiQueued.compareAndSet(false, true)) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            boolean acknowledged = false;
            try {
                if (!disposed.getAsBoolean() && observed.equals(observedMode)) {
                    acknowledged = com.github.claudecodegui.pet.CodexPetFloatingService.reconcileOfficeDisplay(allow);
                }
            } finally {
                boolean complete = acknowledged;
                try {
                    worker.execute(() -> {
                        try {
                            if (complete && !disposed.getAsBoolean() && observed.equals(observedMode) && observed.epoch().equals(modes.read().epoch())) {
                                var acknowledgedMember = new PetOfficeModeStore.Member(member.id(), member.pid(), member.startedAt(), observed.epoch());
                                modes.register(acknowledgedMember);
                                member = acknowledgedMember;
                            }
                        } catch (IOException failure) {
                            com.github.claudecodegui.pet.CodexPetFloatingService.revokeOfficeOverlayPermission();
                        } finally {
                            modeUiQueued.set(false);
                        }
                    });
                } catch (RejectedExecutionException stopped) {
                    modeUiQueued.set(false);
                }
            }
        }, com.intellij.openapi.application.ModalityState.any());
    }

    private void detachAfterModeFailure() {
        observedMode = null;
        com.github.claudecodegui.pet.CodexPetFloatingService.revokeOfficeOverlayPermission();
        if (modeUiQueued.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    com.github.claudecodegui.pet.CodexPetFloatingService.reconcileOfficeDisplay(false);
                } finally {
                    modeUiQueued.set(false);
                }
            }, com.intellij.openapi.application.ModalityState.any());
        }
    }


    void retireAndStop() {
        observedMode = null;
        com.github.claudecodegui.pet.CodexPetFloatingService.revokeOfficeOverlayPermission();
        ApplicationManager.getApplication().invokeLater(() -> {
            boolean detached;
            try {
                detached = com.github.claudecodegui.pet.CodexPetFloatingService.reconcileOfficeDisplay(false);
            } catch (RuntimeException failure) {
                LOG.warn("[PetOffice] OFFICE_MEMBER_DETACH_PENDING");
                worker.shutdown();
                return;
            }
            try {
                worker.execute(() -> {
                    try {
                        if (detached && modes != null && member != null) {
                            modes.retire(member);
                        }
                    } catch (IOException unavailable) {
                        LOG.warn("[PetOffice] OFFICE_MEMBER_CLEANUP_FAILED");
                    }
                });
            } catch (RejectedExecutionException stopped) {
                LOG.warn("[PetOffice] OFFICE_MEMBER_CLEANUP_FAILED");
            } finally {
                worker.shutdown();
            }
        }, ModalityState.any());
    }
}
