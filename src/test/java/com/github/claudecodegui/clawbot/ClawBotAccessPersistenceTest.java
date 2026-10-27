package com.github.claudecodegui.clawbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ClawBotAccessPersistenceTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void settingsRoundTripUsesPrivateAtomicFilesAndPreservesInvalidData() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("private-runtime");
        var store = new ClawBotProgressSettingsStore(root);
        assertEquals(ClawBotProgressSettings.defaults(), store.load());
        var settings = new ClawBotProgressSettings(2, 0, 0);
        store.save(settings);
        assertEquals(settings, new ClawBotProgressSettingsStore(root).load());
        var files = new ClawBotRuntimeFiles(root);
        byte[] invalid = "{\"textIntervalMinutes\":1,\"textIntervalMinutes\":2}".getBytes(StandardCharsets.UTF_8);
        files.write("progress-settings.json", invalid);
        assertThrows(IOException.class, store::load);
        assertArrayEquals(invalid, files.read("progress-settings.json", 16 * 1024));
    }

    @Test
    public void storedCollectionBudgetDoesNotWeakenWireFieldsOrDepth() throws Exception {
        String json = "{" + java.util.stream.IntStream.range(0, 65).mapToObj(i -> "\"field" + i + "\":0")
                .collect(java.util.stream.Collectors.joining(",")) + "}";
        assertEquals(65, ClawBotIpcJson.parseStored(json).getAsJsonObject().size());
        assertThrows(IOException.class, () -> ClawBotIpcJson.parse(json.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ClawBotIpcJson.parseStored("[".repeat(30) + "0" + "]".repeat(30)));
    }
}
