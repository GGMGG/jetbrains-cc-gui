package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class PetOfficeWindowPreferenceControllerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void savesBeforeAcknowledgementAndSerializesDuplicateEdits() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var store = new PetOfficePreferencesStore(root);
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            var controller = new PetOfficeWindowPreferenceController(store, lease, store.read());
            List<Runnable> ui = new ArrayList<>();
            List<PetOfficeWindowPreferenceController.Reply> replies = new ArrayList<>();
            AtomicInteger applications = new AtomicInteger();
            controller.update(patch("initial"), () -> true, next -> applications.incrementAndGet(), ui::add, Runnable::run, replies::add);
            assertEquals("Team Office", store.read().state().preferences().officeName());
            assertTrue(replies.isEmpty());
            assertEquals(0, applications.get());
            controller.update(patch("initial"), () -> true, next -> fail(), ui::add, Runnable::run, replies::add);
            assertEquals("OFFICE_PREFERENCES_BUSY", replies.get(0).error());
            ui.get(0).run();
            assertEquals(1, applications.get());
            assertNull(replies.get(1).error());
            assertEquals("Team Office", replies.get(1).data().get("officeName").getAsString());
            assertEquals(1, replies.get(1).data().get("generation").getAsLong());
        }
    }

    @Test
    public void windowFailureRollsBackTheSavedPreferencesUnderTheSameLease() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var store = new PetOfficePreferencesStore(root);
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            var previous = store.read().state();
            var controller = new PetOfficeWindowPreferenceController(store, lease, store.read());
            List<PetOfficeWindowPreferenceController.Reply> replies = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            controller.update(patch("initial"), () -> true, next -> {
                if (calls.incrementAndGet() == 1) { throw new IllegalStateException("OS refused topmost"); }
                assertEquals(previous.preferences(), next);
            }, Runnable::run, Runnable::run, replies::add);
            assertEquals(2, calls.get());
            assertEquals("OFFICE_WINDOW_UPDATE_FAILED", replies.get(0).error());
            assertEquals(previous.preferences(), store.read().state().preferences());
            assertEquals(previous.bounds(), store.read().state().bounds());
            assertNotEquals(previous.revision(), store.read().state().revision());
        }
    }

    @Test
    public void queuedWindowWorkCannotApplyToARetiredPage() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var store = new PetOfficePreferencesStore(root);
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            var controller = new PetOfficeWindowPreferenceController(store, lease, store.read());
            AtomicBoolean active = new AtomicBoolean(true);
            List<Runnable> ui = new ArrayList<>();
            List<PetOfficeWindowPreferenceController.Reply> replies = new ArrayList<>();
            controller.update(patch("initial"), active::get, next -> fail("Retired page applied native state"), ui::add, Runnable::run, replies::add);
            active.set(false);
            ui.get(0).run();
            assertEquals("OFFICE_PAGE_EXPIRED", replies.get(0).error());
            assertEquals("Team Office", store.read().state().preferences().officeName());
        }
    }

    @Test
    public void aStaleRevisionCannotOverwriteANewerSaveOrGeometry() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var store = new PetOfficePreferencesStore(root);
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            var controller = new PetOfficeWindowPreferenceController(store, lease, store.read());
            controller.saveBounds(new PetOfficePreferencesStore.Bounds(30, 40, 900, 700, true));
            var saved = store.read().state();
            List<PetOfficeWindowPreferenceController.Reply> replies = new ArrayList<>();
            controller.update(patch("initial"), () -> true, next -> fail(), Runnable::run, Runnable::run, replies::add);
            assertEquals("OFFICE_PREFERENCES_CONFLICT", replies.get(0).error());
            assertEquals(saved, store.read().state());
        }
    }

    @Test
    public void aConfirmedReloadReadsTheActualRecordAndAdvancesThePageGeneration() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var store = new PetOfficePreferencesStore(root);
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            var controller = new PetOfficeWindowPreferenceController(store, lease, store.read());
            var saved = store.update("initial", PetOfficeWindowPreferences.changed(store.read().state().preferences(), patch("initial")), null, lease);
            controller.refresh();
            assertEquals(saved.revision(), controller.view().get("revision").getAsString());
            assertEquals(1, controller.view().get("generation").getAsLong());
            controller.refresh();
            assertEquals(1, controller.view().get("generation").getAsLong());
        }
    }

    @Test
    public void windowPatchRejectsUnknownFieldsControlsAndOutOfRangeZoom() throws Exception {
        JsonObject patch = new JsonObject();
        patch.addProperty("expectedRevision", "initial");
        patch.addProperty("officeName", "Team");
        patch.addProperty("topmost", false);
        patch.addProperty("zoom", 125);
        assertEquals(125, PetOfficeWindowPreferences.parse(patch).zoom());
        patch.addProperty("zoom", 74);
        assertThrows(IOException.class, () -> PetOfficeWindowPreferences.parse(patch));
        patch.addProperty("zoom", 100);
        patch.addProperty("officeName", "line\nbreak");
        assertThrows(IOException.class, () -> PetOfficeWindowPreferences.parse(patch));
        patch.addProperty("officeName", "Team");
        patch.addProperty("path", "unregistered");
        assertThrows(IOException.class, () -> PetOfficeWindowPreferences.parse(patch));
    }

    private static PetOfficeWindowPreferences.Patch patch(String revision) {
        return new PetOfficeWindowPreferences.Patch(revision, "Team Office", false, 125);
    }
}
