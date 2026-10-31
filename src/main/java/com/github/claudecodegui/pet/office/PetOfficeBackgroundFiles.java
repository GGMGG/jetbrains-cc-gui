package com.github.claudecodegui.pet.office;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Background authority is restricted to the declared floor files, independent of endpoint discovery. */
final class PetOfficeBackgroundFiles {
    private final PetOfficePrivateFiles files;

    PetOfficeBackgroundFiles(Path root) {
        files = new PetOfficePrivateFiles(root, IntStream.range(0, PetOfficeSessionRegistry.MAX_FLOORS).boxed()
                .collect(Collectors.toUnmodifiableMap(floor -> "background-" + floor + ".bin", floor -> PetOfficeAppearanceService.MAX_IMAGE_BYTES)));
    }

    byte[] readBytes(String name) throws IOException { return files.readBytes(name); }
    long byteLength(String name) throws IOException { return files.byteLength(name); }
    void writeBytes(String name, byte[] bytes, PetOfficeRuntimeLease lease) throws IOException { files.writeBytes(name, bytes, lease); }
    void removeBytes(String name, PetOfficeRuntimeLease lease) throws IOException { files.removePrivate(name, lease); }
}
