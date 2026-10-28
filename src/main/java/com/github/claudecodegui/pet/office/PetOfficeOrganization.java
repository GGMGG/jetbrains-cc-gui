package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.fields;
import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.integer;
import static com.github.claudecodegui.pet.office.PetOfficeJsonCodec.string;

/** Bounded membership and preferred desks. Offline memories do not reserve live seats. */
record PetOfficeOrganization(List<Group> groups, Map<String, Placement> placements, String activeGroup) {
    record Group(String id, String name) { }
    record Placement(String group, int seat) { }

    PetOfficeOrganization {
        groups = List.copyOf(groups);
        placements = Map.copyOf(placements);
    }

    static PetOfficeOrganization empty() { return new PetOfficeOrganization(List.of(), Map.of(), ""); }

    static void validate(JsonObject value) throws IOException {
        fields(value, "groups", "placements", "activeGroup");
        if (!value.get("groups").isJsonArray() || value.getAsJsonArray("groups").size() > PetOfficeSessionRegistry.MAX_SOURCES
                || !value.get("placements").isJsonObject() || value.getAsJsonObject("placements").size() > PetOfficeSessionRegistry.MAX_SOURCES) {
            throw new IOException("OFFICE_GROUPS_INVALID");
        }
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (var raw : value.getAsJsonArray("groups")) {
            if (!raw.isJsonObject()) {
                throw new IOException("OFFICE_GROUPS_INVALID");
            }
            var group = raw.getAsJsonObject();
            fields(group, group.has("icon") ? new String[]{"id", "name", "icon", "theme", "projects"} : new String[]{"id", "name"});
            String id = string(group, "id", 64);
            String name = string(group, "name", 24);
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id) || name.isBlank() || !name.equals(name.strip())
                    || Character.isSpaceChar(name.codePointAt(0)) || name.charAt(0) == 0xfeff
                    || Character.isSpaceChar(name.codePointBefore(name.length())) || name.charAt(name.length() - 1) == 0xfeff
                    || !names.add(name.toLowerCase(Locale.ROOT)) || name.codePoints().allMatch(c -> Character.isSpaceChar(c) || c == 0xfeff)
                    || name.chars().anyMatch(c -> c >= 0x2028 && c <= 0x202e || c >= 0x2066 && c <= 0x2069)
                    || name.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) {
                throw new IOException("OFFICE_GROUPS_INVALID");
            }
            // Existing group names and memberships survive; obsolete room configuration is dropped.
            group.remove("icon");
            group.remove("theme");
            group.remove("projects");
        }
        String active = string(value, "activeGroup", 64);
        if (!active.isEmpty() && !ids.contains(active)) {
            throw new IOException("OFFICE_GROUPS_INVALID");
        }
        for (var entry : value.getAsJsonObject("placements").entrySet()) {
            try {
                PetOfficeSessionRegistry.parseKey(entry.getKey());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("OFFICE_GROUPS_INVALID");
            }
            if (!entry.getValue().isJsonObject()) {
                throw new IOException("OFFICE_GROUPS_INVALID");
            }
            var placement = entry.getValue().getAsJsonObject();
            fields(placement, "group", "seat");
            String group = string(placement, "group", 64);
            integer(placement, "seat", group.isEmpty() ? PetOfficeSessionRegistry.MAX_SOURCES - 1 : 11);
            if (!group.isEmpty() && !ids.contains(group)) {
                throw new IOException("OFFICE_GROUPS_INVALID");
            }
        }
    }
}
