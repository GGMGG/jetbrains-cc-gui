package com.github.claudecodegui.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

public class CodexStorageFilterReviewTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void activeFilterExcludesArchivedSessions() throws Exception {
        var fixture = new CodexStorageFixture(temporary.newFolder().toPath());
        fixture.rollout("current", null, false);
        fixture.rollout("archived", null, true);
        var inventory = new CodexStorageInventory(fixture.paths);
        assertEquals(2, inventory.list(null, 10, null, null, "all").get("total").getAsInt());
        var active = inventory.list(null, 10, null, null, "active");
        assertEquals(1, active.get("total").getAsInt());
        assertEquals("current", active.getAsJsonArray("sessions").get(0).getAsJsonObject().get("sessionId").getAsString());
        assertEquals(1, inventory.list(null, 10, null, null, "archived").get("total").getAsInt());
    }
}
