package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.*;

public class PetOfficePrivateFilesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void filenameAuthorityCannotEscapeItsExplicitBudget() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        assertThrows(IllegalArgumentException.class, () -> new PetOfficePrivateFiles(root, Map.of("..", 100)));
        assertThrows(IllegalArgumentException.class, () -> new PetOfficePrivateFiles(root, Map.of("../outside.json", 100)));
        var files = new PetOfficePrivateFiles(root, Map.of("settings.json", 100));
        assertThrows(IOException.class, () -> files.readPrivate("outside.json"));
        assertThrows(IOException.class, () -> files.readPrivate("../settings.json"));
    }

    @Test
    public void anOversizedReplacementPreservesThePreviousPrivateRecord() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var files = new PetOfficePrivateFiles(root, Map.of("settings.json", 100));
        JsonObject previous = new JsonObject(); previous.addProperty("label", "Team");
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            files.writePrivate("settings.json", previous, lease);
            JsonObject oversized = new JsonObject(); oversized.addProperty("label", "界".repeat(100));
            assertThrows(IOException.class, () -> files.writePrivate("settings.json", oversized, lease));
            assertEquals(previous, files.readPrivate("settings.json"));
        }
    }

    @Test
    public void memberWritesCannotMutateCoordinatorOrPreferenceAuthority() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var files = new PetOfficePrivateFiles(root, Map.of("coordinator-v1.json", 1024));
        assertThrows(IOException.class, () -> files.writeMember("coordinator-v1.json", new JsonObject()));
        assertThrows(IOException.class, () -> files.removeMember("coordinator-v1.json"));
        var members = new PetOfficeMemberFiles(root);
        assertThrows(IOException.class, () -> members.write("../preferences-v1", new JsonObject()));
        assertThrows(IOException.class, () -> members.remove("UPPERCASE"));
    }
}
