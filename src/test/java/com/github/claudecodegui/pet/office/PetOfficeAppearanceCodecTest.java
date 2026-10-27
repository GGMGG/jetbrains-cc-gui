package com.github.claudecodegui.pet.office;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class PetOfficeAppearanceCodecTest {
    @Test
    public void catalogRetainsExactSourceOwnershipAndRejectsOtherParticipants() throws Exception {
        var assets = new PetOfficeAppearanceService(Path.of("."), () -> { throw new AssertionError("Built-in art must not access files"); });
        var appearance = assets.resolve(PetOfficeAppearanceService.defaultConfiguration());
        var catalog = Map.of("idea/tab/binding", appearance);
        byte[] bytes = PetOfficeAppearanceCodec.encode(catalog, "idea");
        assertEquals(catalog, PetOfficeAppearanceCodec.decode(bytes, "idea"));
        assertThrows(IOException.class, () -> PetOfficeAppearanceCodec.decode(bytes, "peer"));
    }

    @Test
    public void rejectsUnsupportedAppearanceFieldsAndNoncanonicalBuiltinManifest() throws Exception {
        var assets = new PetOfficeAppearanceService(Path.of("."), () -> null);
        var appearance = assets.resolve(PetOfficeAppearanceService.defaultConfiguration());
        byte[] bytes = PetOfficeAppearanceCodec.encode(Map.of("idea/tab/binding", appearance), "idea");
        for (String field : new String[] { "scope", "opacity", "manifest" }) {
            var value = PetOfficeIpcFrameCodec.parse(bytes);
            var metadata = value.getAsJsonArray("sources").get(0).getAsJsonObject().getAsJsonObject("appearance");
            if (field.equals("scope")) { metadata.addProperty(field, "arbitrary"); }
            if (field.equals("opacity")) { metadata.addProperty(field, "1"); }
            if (field.equals("manifest")) { metadata.getAsJsonObject(field).addProperty("width", 193); }
            assertThrows(IOException.class, () -> PetOfficeAppearanceCodec.decode(
                    value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "idea"));
        }
    }
}
