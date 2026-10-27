package com.github.claudecodegui.handler;

import org.junit.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CodexStorageProtocolTest {
    @Test
    public void settingsDispatcherPublishesEveryStorageActionExactlyOnce() {
        String[] types = SettingsHandler.supportedTypes();
        Set<String> registered = Set.copyOf(Arrays.asList(types));
        assertEquals(types.length, registered.size());
        assertTrue(registered.contains("get_mode"));
        assertTrue(registered.containsAll(Arrays.asList(CodexStorageHandler.TYPES)));
        assertTrue(registered.containsAll(Arrays.asList(CodexStorageMaintenanceHandler.TYPES)));
        assertTrue(registered.containsAll(Arrays.asList(CodexStorageDeletionHandler.TYPES)));
    }
}
