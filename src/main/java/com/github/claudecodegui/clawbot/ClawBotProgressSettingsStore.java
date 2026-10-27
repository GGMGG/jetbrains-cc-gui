package com.github.claudecodegui.clawbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** Non-sensitive settings use the same private runtime path and atomic replacement policy. */
final class ClawBotProgressSettingsStore {
    private static final String FILE_NAME = "progress-settings.json";
    private static final int MAX_FILE_SIZE = 16 * 1024;
    private final ClawBotRuntimeFiles files;

    ClawBotProgressSettingsStore(Path runtimeDirectory) {
        files = new ClawBotRuntimeFiles(Objects.requireNonNull(runtimeDirectory));
    }

    synchronized ClawBotProgressSettings load() throws IOException {
        files.prepare();
        byte[] bytes = files.read(FILE_NAME, MAX_FILE_SIZE);
        return bytes == null ? ClawBotProgressSettings.defaults() : ClawBotProgressSettings.fromJson(ClawBotIpcJson.parse(bytes));
    }

    synchronized void save(ClawBotProgressSettings settings) throws IOException {
        files.prepare();
        files.write(FILE_NAME, Objects.requireNonNull(settings).toJson().toString().getBytes(StandardCharsets.UTF_8));
    }
}
