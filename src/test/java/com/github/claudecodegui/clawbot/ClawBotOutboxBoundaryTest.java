package com.github.claudecodegui.clawbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClawBotOutboxBoundaryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void changingTheBindingCannotReadCachedPlaintext() throws Exception {
        Path root = ClawBotTestFiles.privateRoot(temporary.newFolder().toPath());
        var store = new ClawBotPendingDeliveryStore(root);
        var delivery = new ClawBotPendingDeliveryStore.Delivery("message", "recipient", "context", "body", true, null);
        store.put(delivery, "first-binding");
        assertThrows(IOException.class, () -> store.pending("replacement-binding"));
        assertEquals(delivery, store.pending("first-binding").get(0));
    }

    @Test public void invalidBatchAdmissionPreservesThePreviousEncryptedFile() throws Exception {
        Path root = ClawBotTestFiles.privateRoot(temporary.newFolder().toPath());
        var store = new ClawBotPendingDeliveryStore(root);
        var valid = new ClawBotPendingDeliveryStore.Delivery("valid", "recipient", "context", "body", true, null);
        store.put(valid, "binding");
        byte[] before = Files.readAllBytes(root.resolve("pending-deliveries.enc"));
        var invalid = new ClawBotPendingDeliveryStore.Delivery("invalid", "recipient", "context", null, true, null);
        assertThrows(IOException.class, () -> store.putAll(java.util.List.of(valid, invalid), "binding"));
        assertArrayEquals(before, Files.readAllBytes(root.resolve("pending-deliveries.enc")));
        assertEquals(java.util.List.of(valid), store.pending("binding"));
    }

    @Test public void fractionalAndDuplicateReceiptFieldsAreRejectedWithoutRewriting() throws Exception {
        for (String created : new String[]{"1.5", "1,\"createdAt\":2"}) {
            Path root = ClawBotTestFiles.privateRoot(temporary.newFolder().toPath());
            Path file = root.resolve("outbound-receipts.json");
            String content = "[{\"clientId\":\"" + UUID.randomUUID() + "\",\"status\":\"SENT\",\"createdAt\":"
                    + created + ",\"updatedAt\":3,\"errorCode\":null}]";
            ClawBotTestFiles.writeString(file, content, StandardCharsets.UTF_8);
            assertThrows(IOException.class, () -> new ClawBotOutboundReceiptStore(root).recent());
            assertEquals(content, Files.readString(file));
        }
    }

    @Test public void persistedFractionalRateLimitTimestampsAreRejected() throws Exception {
        Path root = ClawBotTestFiles.privateRoot(temporary.newFolder().toPath());
        ClawBotTestFiles.writeString(root.resolve("outbound-protection.json"),
                "{\"attempts\":[1.5],\"cooldownUntil\":0}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new ClawBotOutboundProtection(root).acquire(10, false));
    }

    @Test public void arbitraryEncryptedFileNamesAreRejected() throws Exception {
        Path root = ClawBotTestFiles.privateRoot(temporary.newFolder().toPath());
        assertThrows(IOException.class, () -> new ClawBotPendingDeliveryStore(root, "../outside.enc").pending("binding"));
        try (var files = Files.list(root)) { assertTrue(files.findAny().isEmpty()); }
    }
}
