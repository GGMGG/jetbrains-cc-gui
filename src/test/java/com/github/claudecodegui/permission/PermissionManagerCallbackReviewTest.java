package com.github.claudecodegui.permission;

import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PermissionManagerCallbackReviewTest {
    @Test
    public void channelDecisionCompletesOutsideRequestMonitor() {
        PermissionManager manager = new PermissionManager();
        assertCallbackOutsideMonitor(manager,
                request -> manager.handlePermissionDecision(request.getChannelId(), true, true, null));
    }

    @Test
    public void alwaysDecisionCompletesOutsideRequestMonitor() {
        PermissionManager manager = new PermissionManager();
        assertCallbackOutsideMonitor(manager,
                request -> manager.handlePermissionDecisionAlways(request.getChannelId(), false));
    }

    @Test
    public void exactDecisionCompletesOutsideRequestMonitor() {
        PermissionManager manager = new PermissionManager();
        assertCallbackOutsideMonitor(manager,
                request -> manager.handlePermissionDecision(request, true, true, null));
    }

    @Test
    public void remoteDecisionCompletesOutsideRequestMonitor() {
        PermissionManager manager = new PermissionManager();
        assertCallbackOutsideMonitor(manager,
                request -> assertTrue(manager.tryHandleRemotePermissionDecision(request, false, null)));
    }

    @Test
    public void supersededDecisionCompletesOutsideRequestMonitor() {
        PermissionManager manager = new PermissionManager();
        assertCallbackOutsideMonitor(manager, request -> {
            PermissionRequest replacement = manager.createRequest("channel", "Write", Map.of(), null, null);
            manager.handlePermissionDecision(request, true, true, null);
            assertFalse(replacement.isResolved());
            assertTrue(manager.getPendingRequests().contains(replacement));
        });
    }

    private static void assertCallbackOutsideMonitor(PermissionManager manager, Consumer<PermissionRequest> resolve) {
        PermissionRequest request = manager.createRequest("channel", "Write", Map.of(), null, null);
        CompletableFuture<Void> callback = request.getResultFuture().thenAccept(result ->
                assertFalse("Inline callbacks must be able to acquire the dialog lock without owning the request lock",
                        Thread.holdsLock(request)));
        resolve.accept(request);
        callback.join();
        assertTrue(request.isResolved());
    }
}
