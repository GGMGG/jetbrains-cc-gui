package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded, current-user overlay markers; this store cannot mutate discovery or preferences. */
final class PetOfficeMemberFiles {
    private final Path root;

    PetOfficeMemberFiles(Path root) { this.root = root.toAbsolutePath().normalize(); }

    JsonObject read(String id) throws IOException { return files(id).readPrivate(name(id)); }

    void write(String id, JsonObject value) throws IOException { files(id).writeMember(name(id), value); }

    void remove(String id) throws IOException { files(id).removeMember(name(id)); }

    List<String> ids() throws IOException {
        // Validate the namespace and every parent even when it contains no member files.
        new PetOfficePrivateFiles(root, Map.of("control-v1.json", 8192)).validateDirectory();
        List<String> result = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(root, "overlay-*.json")) {
            for (Path entry : entries) {
                if (result.size() >= 64) {
                    throw new IOException("OFFICE_PARTICIPANT_CAPACITY");
                }
                String file = entry.getFileName().toString();
                String id = file.substring(8, file.length() - 5);
                if (!file.equals(name(id))) {
                    throw new IOException("OFFICE_PARTICIPANT_INVALID");
                }
                result.add(id);
            }
        }
        return List.copyOf(result);
    }

    static String name(String id) throws IOException {
        try {
            if (!UUID.fromString(id).toString().equals(id)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("OFFICE_PARTICIPANT_INVALID");
        }
        return "overlay-" + id + ".json";
    }

    private PetOfficePrivateFiles files(String id) throws IOException {
        return new PetOfficePrivateFiles(root, Map.of(name(id), 1024));
    }
}
