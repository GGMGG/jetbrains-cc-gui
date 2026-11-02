package com.github.claudecodegui.handler;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexStorageScanTest {
    @Test public void cancellationBeforeWorkerStartIsRetained() {
        var handler = new CodexStorageHandler(null);
        var scan = handler.prepareScan("{\"requestId\":\"scan-fixture\"}");
        handler.cancelScan("scan-fixture");
        assertTrue(scan.cancelled().get());
        handler.finishScan(scan);
    }

    @Test public void duplicateAndStaleCompletionsCannotRemoveAnotherScansCancellation() {
        var handler = new CodexStorageHandler(null);
        var first = handler.prepareScan("{\"requestId\":\"same-id\"}");
        var duplicate = handler.prepareScan("{\"requestId\":\"same-id\"}");
        assertEquals("REQUEST_ID_CONFLICT", duplicate.error());
        handler.finishScan(duplicate);
        handler.cancelScan("same-id");
        assertTrue(first.cancelled().get());
        handler.finishScan(first);
        var replacement = handler.prepareScan("{\"requestId\":\"same-id\"}");
        assertFalse(replacement.cancelled().get());
        handler.finishScan(first);
        handler.cancelScan("same-id");
        assertTrue(replacement.cancelled().get());
        handler.finishScan(replacement);
    }

    @Test public void disposingTheHandlerCancelsAlreadyQueuedScans() {
        var handler = new CodexStorageHandler(null);
        var first = handler.prepareScan("{\"requestId\":\"first\"}");
        var second = handler.prepareScan("{\"requestId\":\"second\"}");
        handler.dispose();
        assertTrue(first.cancelled().get());
        assertTrue(second.cancelled().get());
    }
}
