package com.github.claudecodegui.pet.office;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Only the live desktop lease may replace or retire the private discovery record. */
final class PetOfficeEndpointStore {
    private static final String FILE = "coordinator-v1.json";
    private final PetOfficePrivateFiles files;

    PetOfficeEndpointStore(Path root) {
        files = new PetOfficePrivateFiles(root, Map.of(FILE, 1024));
    }

    PetOfficeEndpoint read() throws IOException {
        var record = files.readPrivate(FILE);
        return record == null ? null : PetOfficeEndpoint.decode(record);
    }

    void publish(PetOfficeEndpoint endpoint, PetOfficeRuntimeLease lease) throws IOException {
        files.writePrivate(FILE, PetOfficeEndpoint.encode(endpoint), lease);
    }

    void removeOwned(PetOfficeEndpoint endpoint, PetOfficeRuntimeLease lease) throws IOException {
        Objects.requireNonNull(lease, "lease");
        synchronized (lease) {
            if (!lease.isValid()) {
                throw new IOException("OFFICE_LEADER_LOST");
            }
            if (endpoint.equals(read())) {
                files.removePrivate(FILE, lease);
            }
        }
    }
}
