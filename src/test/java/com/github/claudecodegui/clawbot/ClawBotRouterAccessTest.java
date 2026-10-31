package com.github.claudecodegui.clawbot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClawBotRouterAccessTest {
    @Test
    public void blocksUnlistedSendersButAllowsWhoamiAndAuthorizedRouting() throws Exception {
        FakeSecretBackend backend = new FakeSecretBackend();
        ClawBotSenderAccessStore accessStore = new ClawBotSenderAccessStore(backend);
        accessStore.allow("authorized-user");
        ClawBotMessageRouter router = new ClawBotMessageRouter(
                new ClawBotConversationRouteStore(backend), accessStore::isAllowed);
        List<String> replies = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        ClawBotMessageRouter.ReplySender sender = (userId, contextToken, text) -> replies.add(text);
        ClawBotMessageRouter.MessageEnqueuer enqueuer = (handle, inbound) -> {
            delivered.add(handle + ":" + inbound.text());
            return true;
        };
        List<ClawBotSessionSnapshot> sessions = List.of(session("session-1", "Project", "codex"));

        router.handle(new ClawBotInboundMessage("denied", "unlisted-user", "ctx-1", "/sessions"),
                sessions, sender, enqueuer);
        assertTrue(replies.isEmpty());
        assertTrue(delivered.isEmpty());

        router.handle(new ClawBotInboundMessage("whoami", "unlisted-user", "ctx-2", "/whoami"),
                sessions, sender, enqueuer);
        assertTrue(replies.get(0).contains("unlisted-user"));
        assertTrue(delivered.isEmpty());

        router.handle(new ClawBotInboundMessage("selection", "authorized-user", "ctx-3", "/use session-1"),
                sessions, sender, enqueuer);
        router.handle(new ClawBotInboundMessage("authorized", "authorized-user", "ctx-3", "hello"),
                sessions, sender, enqueuer);
        assertEquals(List.of("session-1:hello"), delivered);
        assertFalse(replies.get(0).contains("authorized-user"));
    }

    @Test
    public void senderRouteRequiresReselectionAfterRouterRecreation() throws Exception {
        FakeSecretBackend backend = new FakeSecretBackend();
        ClawBotConversationRouteStore routeStore = new ClawBotConversationRouteStore(backend);
        ClawBotSessionSnapshot first = session("session-1", "Project A", "claude");
        ClawBotSessionSnapshot second = session("session-2", "Project B", "codex");
        List<ClawBotSessionSnapshot> sessions = List.of(first, second);
        ClawBotMessageRouter firstRouter = new ClawBotMessageRouter(routeStore, senderId -> true);

        firstRouter.handle(new ClawBotInboundMessage("select", "sender-1", "select-context", "/use session-2"),
                sessions, (userId, contextToken, text) -> { }, (handle, inbound) -> true);

        ClawBotMessageRouter restartedRouter = new ClawBotMessageRouter(
                new ClawBotConversationRouteStore(backend), senderId -> true);
        List<String> delivered = new ArrayList<>();
        restartedRouter.handle(new ClawBotInboundMessage("message", "sender-1", "new-context", "hello"),
                sessions, (userId, contextToken, text) -> { }, (handle, inbound) -> {
                    delivered.add(handle);
                    return true;
                });

        assertTrue(delivered.isEmpty());
    }

    private static final class FakeSecretBackend implements ClawBotCredentialStore.SecretBackend {

        private final java.util.Map<String, String> values = new java.util.HashMap<>();

        @Override
        public String read(String key) {
            return values.get(key);
        }

        @Override
        public void write(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void clear(String key) {
            values.remove(key);
        }
    }

    private static ClawBotSessionSnapshot session(String handle, String name, String provider) {
        return new ClawBotSessionSnapshot(
                handle, "ide-1", "project-1", name, provider,
                Set.of("STATUS", "INBOUND", "ROUTING_V2"), ClawBotSessionStatus.ONLINE,
                1, 1, 1, "Chat", "generation-1", 0);
    }

    private static ClawBotSessionSnapshot controllableSession(
            String handle, String project, String tab, String provider) {
        return new ClawBotSessionSnapshot(
                handle, "ide-1", "project-1", project, provider,
                Set.of("STATUS", "INBOUND", "CONTROL", "ROUTING_V2"), ClawBotSessionStatus.ONLINE,
                1, 1, 1, tab, "generation-1", 0);
    }
}
