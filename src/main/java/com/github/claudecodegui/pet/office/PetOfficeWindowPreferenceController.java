package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Serial preference commits with an EDT acknowledgement and a revision-checked rollback on window failure. */
final class PetOfficeWindowPreferenceController {
    interface WindowTarget { void apply(PetOfficePreferencesStore.Preferences preferences); }
    record Reply(JsonObject data, String error) { }
    private record Current(PetOfficePreferencesStore.State state, long generation, boolean recovered) { }
    private final PetOfficePreferencesStore store;
    private final PetOfficeRuntimeLease lease;
    private final AtomicBoolean updating = new AtomicBoolean();
    private volatile Current current;

    PetOfficeWindowPreferenceController(PetOfficePreferencesStore store, PetOfficeRuntimeLease lease, PetOfficePreferencesStore.Loaded loaded) {
        this.store = store;
        this.lease = lease;
        current = new Current(loaded.state(), 0, loaded.recovered());
    }

    PetOfficePreferencesStore.State state() {
        return current.state();
    }

    JsonObject view() {
        Current value = current;
        return PetOfficeWindowPreferences.view(value.state(), value.generation(), value.recovered());
    }

    void refresh() throws IOException {
        if (!updating.get()) {
            reload();
        }
    }

    private void reload() throws IOException {
        synchronized (lease) {
            var loaded = store.read();
            synchronized (this) {
                if (!loaded.state().equals(current.state()) || loaded.recovered() != current.recovered()) {
                    current = new Current(loaded.state(), current.generation() + 1, loaded.recovered());
                }
            }
        }
    }

    private synchronized void publish(PetOfficePreferencesStore.State state) {
        current = new Current(state, current.generation() + 1, false);
    }

    /** The caller enters on its bounded IO worker; no IO worker waits for the EDT. */
    void update(PetOfficeWindowPreferences.Patch patch, BooleanSupplier active, WindowTarget target,
                Consumer<Runnable> ui, Consumer<Runnable> io, Consumer<Reply> completed) {
        if (!updating.compareAndSet(false, true)) {
            completed.accept(new Reply(null, "OFFICE_PREFERENCES_BUSY"));
            return;
        }
        PetOfficePreferencesStore.State previous;
        PetOfficePreferencesStore.State saved;
        try {
            if (!active.getAsBoolean()) {
                throw new IOException("OFFICE_PAGE_EXPIRED");
            }
            synchronized (lease) {
                previous = store.read().state();
                saved = store.update(patch.expectedRevision(), PetOfficeWindowPreferences.changed(previous.preferences(), patch), null, lease);
            }
        } catch (IOException | RuntimeException failure) {
            finish(completed, code(failure));
            return;
        }
        try {
            ui.accept(() -> {
                if (!active.getAsBoolean()) {
                    // The accepted preference remains readable on reopen; the retired page has no UI authority.
                    publish(saved);
                    finish(completed, "OFFICE_PAGE_EXPIRED");
                    return;
                }
                try {
                    target.apply(saved.preferences());
                } catch (RuntimeException | LinkageError failure) {
                    try {
                        target.apply(previous.preferences());
                    } catch (RuntimeException | LinkageError restoreFailed) {
                        // The response still reports failure; persistence is restored below under the original lease.
                    }
                    rollback(previous, saved, io, completed);
                    return;
                }
                publish(saved);
                finish(completed, null);
            });
        } catch (RuntimeException unavailable) {
            rollback(previous, saved, io, completed);
        }
    }

    private void rollback(PetOfficePreferencesStore.State previous, PetOfficePreferencesStore.State saved,
                          Consumer<Runnable> io, Consumer<Reply> completed) {
        try {
            io.accept(() -> {
                try {
                    publish(store.update(saved.revision(), previous.preferences(), previous.bounds(), lease));
                    finish(completed, "OFFICE_WINDOW_UPDATE_FAILED");
                } catch (IOException | RuntimeException uncertain) {
                    try {
                        reload();
                    } catch (IOException | RuntimeException unavailable) {
                        // A damaged record is never presented as a new confirmed value.
                    }
                    finish(completed, "OFFICE_PREFERENCES_UNCERTAIN");
                }
            });
        } catch (RuntimeException unavailable) {
            finish(completed, "OFFICE_PREFERENCES_UNCERTAIN");
        }
    }

    /** Debounced geometry updates preserve all other preferences and are suppressed during a user edit commit. */
    void saveBounds(PetOfficePreferencesStore.Bounds bounds) throws IOException {
        if (updating.get()) {
            return;
        }
        synchronized (lease) {
            var state = store.read().state();
            if (!state.bounds().equals(bounds)) {
                publish(store.update(state.revision(), null, bounds, lease));
            }
        }
    }

    private void finish(Consumer<Reply> completed, String error) {
        updating.set(false);
        completed.accept(new Reply(error == null ? view() : null, error));
    }

    private static String code(Exception failure) {
        String code = failure.getMessage();
        return code != null && code.matches("OFFICE_[A-Z_]{1,56}") ? code : "OFFICE_PREFERENCES_UNAVAILABLE";
    }
}
