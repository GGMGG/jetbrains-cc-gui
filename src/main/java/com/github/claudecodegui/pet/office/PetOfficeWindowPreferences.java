package com.github.claudecodegui.pet.office;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;

import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.fields;
import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.identifier;
import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.integer;
import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.string;

/** Window-only edits preserve the remaining persisted preference schema for subsequent Office slices. */
final class PetOfficeWindowPreferences {
    record Patch(String expectedRevision, String officeName, boolean topmost, int zoom) { }
    record View(String revision, long generation, String officeName, boolean topmost, int zoom, boolean recovered) { }

    private PetOfficeWindowPreferences() { }

    static Patch parse(JsonObject payload) throws IOException {
        fields(payload, "expectedRevision", "officeName", "topmost", "zoom");
        String revision = identifier(payload, "expectedRevision", false);
        String name = string(payload, "officeName", 64);
        if (!payload.get("topmost").isJsonPrimitive() || !payload.getAsJsonPrimitive("topmost").isBoolean()) {
            throw new IOException("OFFICE_PREFERENCES_INVALID");
        }
        int zoom = (int) integer(payload, "zoom", 150);
        if (zoom < 75) {
            throw new IOException("OFFICE_PREFERENCES_INVALID");
        }
        return new Patch(revision, name, payload.get("topmost").getAsBoolean(), zoom);
    }

    static PetOfficePreferencesStore.Preferences changed(PetOfficePreferencesStore.Preferences current, Patch patch) {
        return new PetOfficePreferencesStore.Preferences(current.limitMiB(), current.floors(), current.floor(), current.product(), current.search(),
                current.showIdle(), current.paused(), patch.topmost(), patch.zoom(), current.presentation(), current.appearanceOverrides(),
                patch.officeName(), current.employeeNames(), current.organization());
    }

    static JsonObject view(PetOfficePreferencesStore.State state, long generation, boolean recovered) {
        var p = state.preferences();
        return new Gson().toJsonTree(new View(state.revision(), generation, p.officeName(), p.topmost(), p.zoom(), recovered)).getAsJsonObject();
    }
}
