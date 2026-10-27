package com.github.claudecodegui.clawbot;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

/** Only called with test-owned TemporaryFolder paths. */
final class ClawBotTestFiles {
    private ClawBotTestFiles() { }
    static Path privateRoot(Path path) throws IOException {
        ClawBotRuntimeFiles.restrict(path, true);
        return path;
    }
    static Path writeString(Path path, CharSequence value, Charset charset, OpenOption... options) throws IOException {
        Files.writeString(path, value, charset, options);
        ClawBotRuntimeFiles.restrict(path, false);
        return path;
    }
}
