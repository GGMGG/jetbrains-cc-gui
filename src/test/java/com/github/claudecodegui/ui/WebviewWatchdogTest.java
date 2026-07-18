package com.github.claudecodegui.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebviewWatchdogTest {

    @Test
    public void usesShortTimeoutBeforeFrontendReady() {
        long startupTimeout = WebviewWatchdog.heartbeatTimeoutMs(false, false);

        assertEquals(startupTimeout, WebviewWatchdog.heartbeatTimeoutMs(false, true));
        assertTrue(startupTimeout < WebviewWatchdog.heartbeatTimeoutMs(true, false));
    }

    @Test
    public void extendsHeartbeatTimeoutOnlyForReadyStreamingWebview() {
        assertTrue(WebviewWatchdog.heartbeatTimeoutMs(true, true)
                > WebviewWatchdog.heartbeatTimeoutMs(true, false));
    }

    @Test
    public void retriesStartupRecoverySoonerThanRuntimeRecovery() {
        assertTrue(WebviewWatchdog.recoveryCooldownMs(false)
                < WebviewWatchdog.recoveryCooldownMs(true));
    }

    @Test
    public void monitorsStartupEvenWhenPanelIsHiddenOrUnfocused() {
        assertTrue(WebviewWatchdog.shouldMonitor(false, false, false, false));
    }

    @Test
    public void pausesRuntimeMonitoringWhenWebviewCannotRenderVisibly() {
        assertTrue(WebviewWatchdog.shouldMonitor(true, true, true, true));
        assertFalse(WebviewWatchdog.shouldMonitor(true, false, true, true));
        assertFalse(WebviewWatchdog.shouldMonitor(true, true, false, true));
        // Editor focus is independent from Webview render health.
        assertTrue(WebviewWatchdog.shouldMonitor(true, true, true, false));
    }
}
