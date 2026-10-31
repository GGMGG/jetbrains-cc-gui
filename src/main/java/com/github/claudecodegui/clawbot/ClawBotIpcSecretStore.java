package com.github.claudecodegui.clawbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

/** Private loopback authentication secret, separate from non-sensitive leader metadata. */
final class ClawBotIpcSecretStore {
    private final ClawBotRuntimeFiles files;

    ClawBotIpcSecretStore(Path root) { files = new ClawBotRuntimeFiles(root); }

    String loadOrCreate() throws IOException {
        files.prepare();
        Path path = files.path("gateway.auth");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return read();
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try {
            Files.createFile(path);
        } catch (java.nio.file.FileAlreadyExistsException raced) {
            return read();
        }
        try {
            ClawBotRuntimeFiles.restrict(path, false);
            Files.writeString(path, token, StandardCharsets.US_ASCII, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            Files.deleteIfExists(path);
            throw failure;
        }
        return token;
    }

    String read() throws IOException {
        byte[] bytes = files.read("gateway.auth", 512);
        if (bytes == null) {
            throw new IOException("CLAWBOT_IPC_SECRET_UNAVAILABLE");
        }
        String token = new String(bytes, StandardCharsets.US_ASCII);
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IOException("CLAWBOT_IPC_SECRET_INVALID");
        }
        return token;
    }
}
