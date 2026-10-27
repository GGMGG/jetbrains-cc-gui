package com.github.claudecodegui.provider.minimax;

import com.github.claudecodegui.provider.common.SessionHistoryNotFoundException;
import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MiniMaxHistoryReaderRestoreTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void missingStartupTranscriptIsNotAnEmptySuccess() throws Exception {
        MiniMaxHistoryReader reader = new MiniMaxHistoryReader(temporary.newFolder("minimax-home").toPath(), new Gson());
        assertTrue(reader.getSessionMessages("missing-session", "/fixture").isEmpty());
        assertThrows(SessionHistoryNotFoundException.class,
                () -> reader.getSessionMessages("missing-session", "/fixture", () -> false));
    }
}
