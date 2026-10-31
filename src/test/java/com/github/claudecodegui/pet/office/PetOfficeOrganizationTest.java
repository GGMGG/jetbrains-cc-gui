package com.github.claudecodegui.pet.office;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PetOfficeOrganizationTest {
    private static final Gson JSON = new Gson();

    private static JsonObject preferences() {
        return JSON.toJsonTree(PetOfficePreferencesStore.defaults().preferences()).getAsJsonObject();
    }

    private static PetOfficeOrganization.Group group(String id) {
        return new PetOfficeOrganization.Group(id, "Team " + id);
    }

    @Test
    public void legacyPreferencesGainEmptyRoomsAndGroupsAreImmutable() throws Exception {
        var value = preferences();
        value.remove("organization");
        assertEquals(PetOfficeOrganization.empty(), PetOfficePreferencesStore.preferences(value).organization());
        var groups = new java.util.ArrayList<>(List.of(group("a")));
        var placements = new java.util.HashMap<>(Map.of("ide/tab/old", new PetOfficeOrganization.Placement("a", 0)));
        var organization = new PetOfficeOrganization(groups, placements, "a");
        groups.clear();
        placements.clear();
        assertEquals(1, organization.groups().size());
        assertEquals(1, organization.placements().size());
        assertThrows(UnsupportedOperationException.class, () -> organization.placements().clear());
    }

    @Test
    public void preferredDesksStayBoundedButOfflineMemoriesCanShareADesk() throws Exception {
        var placements = new java.util.LinkedHashMap<String, PetOfficeOrganization.Placement>();
        for (int index = 0; index < 12; index++) {
            placements.put("ide/tab/binding-" + index, new PetOfficeOrganization.Placement("a", index));
        }
        var value = preferences();
        value.add("organization", JSON.toJsonTree(new PetOfficeOrganization(List.of(group("a")), placements, "a")));
        var parsed = PetOfficePreferencesStore.preferences(value);
        assertEquals(12, parsed.organization().placements().size());
        placements.put("ide/tab/overflow", new PetOfficeOrganization.Placement("a", 12));
        value.add("organization", JSON.toJsonTree(new PetOfficeOrganization(List.of(group("a")), placements, "a")));
        assertThrows(IOException.class, () -> PetOfficePreferencesStore.preferences(value));
        placements.put("ide/tab/overflow", new PetOfficeOrganization.Placement("a", 0));
        value.add("organization", JSON.toJsonTree(new PetOfficeOrganization(List.of(group("a")), placements, "a")));
        assertEquals(13, PetOfficePreferencesStore.preferences(value).organization().placements().size());
    }

    @Test
    public void persistentRoomsAdmitLongBindingsAndMoreThan32PlacementsOnlyAtTheirTypedLocation() throws Exception {
        var placements = new java.util.LinkedHashMap<String, PetOfficeOrganization.Placement>();
        String longest = "i".repeat(128) + "/" + "t".repeat(128) + "/" + "b".repeat(128);
        placements.put(longest, new PetOfficeOrganization.Placement("a", 0));
        for (int index = 1; index <= 40; index++) {
            placements.put("ide/tab/binding-" + index, new PetOfficeOrganization.Placement("", index));
        }
        var value = preferences();
        value.add("organization", JSON.toJsonTree(new PetOfficeOrganization(List.of(group("a")), placements, "a")));
        var root = new JsonObject();
        root.add("preferences", value);
        var decoded = PetOfficeIpcFrameCodec.parse(root.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(41, PetOfficePreferencesStore.preferences(decoded.getAsJsonObject("preferences")).organization().placements().size());
        assertThrows(IOException.class, () -> PetOfficeIpcFrameCodec.parse(value.getAsJsonObject("organization").toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void namesReferencesAndPreferredDesksAreValidated() throws Exception {
        var groupA = group("a");
        for (var bad : List.of(
                new PetOfficeOrganization(List.of(groupA), Map.of("ide/tab/old", new PetOfficeOrganization.Placement("missing", 0)), "a"),
                new PetOfficeOrganization(List.of(groupA), Map.of(), "missing"),
                new PetOfficeOrganization(List.of(groupA, groupA), Map.of(), "a"),
                new PetOfficeOrganization(List.of(new PetOfficeOrganization.Group("a", "\u202ehidden")), Map.of(), "a"),
                new PetOfficeOrganization(List.of(groupA, new PetOfficeOrganization.Group("b", "TEAM A")), Map.of(), "a"))) {
            var value = preferences();
            value.add("organization", JSON.toJsonTree(bad));
            assertThrows(IOException.class, () -> PetOfficePreferencesStore.preferences(value));
        }
        var unicode = new PetOfficeOrganization(List.of(new PetOfficeOrganization.Group("a", "🐾".repeat(24))), Map.of(), "a");
        var value = preferences();
        value.add("organization", JSON.toJsonTree(unicode));
        assertTrue(PetOfficePreferencesStore.preferences(value).organization().groups().get(0).name().startsWith("🐾"));
    }

    @Test
    public void oldRoomConfigurationMigratesToNamesAndMemberships() throws Exception {
        var value = preferences();
        var organization = JSON.toJsonTree(new PetOfficeOrganization(List.of(group("a")), Map.of("ide/tab/binding", new PetOfficeOrganization.Placement("a", 3)), "a")).getAsJsonObject();
        var group = organization.getAsJsonArray("groups").get(0).getAsJsonObject();
        group.addProperty("icon", "rocket");
        group.addProperty("theme", "space");
        group.add("projects", JSON.toJsonTree(List.of("project-A")));
        value.add("organization", organization);
        var migrated = PetOfficePreferencesStore.preferences(value).organization();
        assertEquals(group("a"), migrated.groups().get(0));
        assertEquals(new PetOfficeOrganization.Placement("a", 3), migrated.placements().get("ide/tab/binding"));
        assertEquals(2, JSON.toJsonTree(migrated.groups().get(0)).getAsJsonObject().size());
    }

    @Test
    public void windowOnlyEditsPreserveMembershipAndEmployeeNames() throws Exception {
        var value = preferences();
        value.add("organization", JSON.toJsonTree(new PetOfficeOrganization(List.of(group("a")),
                Map.of("ide/tab/binding", new PetOfficeOrganization.Placement("a", 3)), "a")));
        value.add("employeeNames", JSON.toJsonTree(Map.of("ide/tab/binding", "Employee")));
        var current = PetOfficePreferencesStore.preferences(value);
        var patched = PetOfficeWindowPreferences.changed(current,
                new PetOfficeWindowPreferences.Patch("initial", "Renamed office", !current.topmost(), 125));
        assertEquals(current.organization(), patched.organization());
        assertEquals(current.employeeNames(), patched.employeeNames());
    }
}
