package com.github.claudecodegui.pet.office;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.junit.Assert.*;

public class PetOfficeEndpointStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void anOldLeaseOrDiscoveryRecordCannotDeleteItsSuccessor() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var store = new PetOfficeEndpointStore(root);
        var old = new PetOfficeEndpoint(1234, "epoch-1", "a".repeat(64), "idea");
        var next = new PetOfficeEndpoint(1235, "epoch-2", "b".repeat(64), "pycharm");
        var retired = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow();
        assertNull(store.read());
        store.publish(old, retired);
        assertEquals(old, store.read());
        retired.close();
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            store.publish(next, lease);
            assertThrows(IOException.class, () -> store.removeOwned(old, retired));
            assertThrows(IOException.class, () -> store.publish(old, retired));
            store.removeOwned(old, lease);
            assertEquals(next, store.read());
            store.removeOwned(next, lease);
            assertNull(store.read());
        }
    }

    @Test
    public void invalidEndpointReplacementPreservesThePublishedRecord() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var store = new PetOfficeEndpointStore(root);
        var endpoint = new PetOfficeEndpoint(1234, "epoch-1", "c".repeat(64), "idea");
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            store.publish(endpoint, lease);
            assertThrows(IOException.class, () -> store.publish(new PetOfficeEndpoint(0, "epoch-2", "invalid", "idea"), lease));
            assertEquals(endpoint, store.read());
        }
    }
}
