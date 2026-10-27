package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClawBotProgressSettingsTest {
    @Test
    public void rejectsFractionalOverflowAndCoercedIntervalsWithoutRounding() {
        for (String value : new String[] { "1.00000000000000000001", "4294967297", "1e100", "\"1\"", "true" }) {
            JsonObject object = new JsonObject();
            object.add("textIntervalMinutes", JsonParser.parseString(value));
            assertThrows(IOException.class, () -> ClawBotProgressSettings.fromJson(object));
        }
    }

    @Test
    public void migratesOldValidIntervalsAndPreservesIndependentValues() throws Exception {
        JsonObject object = new JsonObject();
        object.addProperty("textIntervalMinutes", 100);
        object.addProperty("idleReminderMinutes", 0);
        object.addProperty("waitReminderMinutes", 0);
        object.addProperty("initialCheckDelaySeconds", 1);
        object.addProperty("maxNotifications", 20);
        var settings = ClawBotProgressSettings.fromJson(object);
        assertEquals(60, settings.textIntervalMinutes());
        assertEquals(0, settings.idleReminderMinutes());
        assertEquals(0, settings.waitReminderMinutes());
        assertEquals(15, settings.initialCheckDelaySeconds());
        assertEquals(6, settings.maxNotifications());
        assertEquals(100, object.get("textIntervalMinutes").getAsInt());
    }
}
