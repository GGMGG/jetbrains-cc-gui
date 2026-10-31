package com.github.claudecodegui.pet.office;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class PetOfficeCoordinatorTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void realPeersAggregateOnlyAfterExactOverlayAcknowledgementAndExitRestoresOverlayAfterClose() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        var modes = new PetOfficeModeStore(root); var member = PetOfficeTestRoot.member(); modes.register(member);
        var shown = new AtomicInteger();
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            var owner = PetOfficeCoordinator.start(root, lease, "owner", () -> true, shown::incrementAndGet, () -> { }, null, true);
            try (var idea = new PetOfficeIpcClient(owner.server().endpoint(), "idea");
                 var pycharm = new PetOfficeIpcClient(owner.server().endpoint(), "pycharm")) {
                assertFalse(modes.commitOffice(owner.entering(), lease));
                modes.register(new PetOfficeModeStore.Member(member.id(), member.pid(), member.startedAt(), owner.entering().epoch()));
                owner.awaitDisplayReady(() -> true);
                assertEquals(PetOfficeModeStore.Mode.OFFICE, modes.read().mode());
                idea.publish(PetOfficeParticipantFixture.snapshot("idea", 1, 1, 1));
                pycharm.publish(PetOfficeParticipantFixture.snapshot("pycharm", 1, 1, 1));
                var scene = PetOfficeSceneCodec.encode(owner.registry().view("owner", owner.server().endpoint().leaderEpoch()));
                assertEquals(2, scene.getAsJsonArray("sources").size());
                assertEquals(3, scene.getAsJsonArray("participants").size());
                pycharm.show(); assertEquals(1, shown.get());
                owner.presentation(false, false);
                assertFalse(owner.recovery().read().enabled());
                assertEquals(PetOfficeModeStore.Mode.EXITING_OFFICE, modes.read().mode());
                owner.close();
                assertEquals(PetOfficeModeStore.Mode.IDE_OVERLAY, modes.read().mode());
                assertNull(new PetOfficeEndpointStore(root).read());
                assertTrue(lease.isValid());
                assertThrows(IOException.class, idea::heartbeat);
            } finally {
                owner.close();
            }
        }
    }

    @Test
    public void hiddenOptInSurvivesAStoppedCoordinatorWithoutPromotingVisibility() throws Exception {
        var root = PetOfficeTestRoot.create(temporary);
        PetOfficeRecoveryStore.Intent hidden;
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            var owner = PetOfficeCoordinator.start(root, lease, "owner", () -> true, () -> { }, () -> { }, null, true);
            owner.awaitDisplayReady(() -> true); owner.presentation(true, false);
            hidden = owner.recovery().read(); owner.close();
            assertTrue(hidden.enabled()); assertFalse(hidden.visible());
            assertEquals(PetOfficeModeStore.Mode.OFFICE, new PetOfficeModeStore(root).read().mode());
        }
        try (var lease = PetOfficeRuntimeLease.tryAcquire(root).orElseThrow()) {
            var next = PetOfficeCoordinator.start(root, lease, "next-owner", () -> true, () -> fail("Recovery must not show the window"), () -> { }, hidden, false);
            try {
                assertTrue(next.recovery().read().enabled()); assertFalse(next.recovery().read().visible());
                assertNotEquals(hidden.leaderEpoch(), next.recovery().read().leaderEpoch());
                next.awaitDisplayReady(() -> true); next.presentation(false, false);
            } finally { next.close(); }
        }
    }

    @Test
    public void rendererProjectionCannotIntroduceAnUnlistedOwner() throws Exception {
        var own = PetOfficeParticipantFixture.snapshot("idea", 1, 1, 1);
        assertThrows(IOException.class, () -> PetOfficeSceneCodec.encode(new PetOfficeParticipantRegistry.View(own, List.of("owner"))));
        assertThrows(IOException.class, () -> PetOfficeSceneCodec.encode(new PetOfficeParticipantRegistry.View(own, List.of("idea", "idea"))));
        var scene = PetOfficeSceneCodec.encode(new PetOfficeParticipantRegistry.View(own, List.of("idea")));
        assertEquals("idea", scene.getAsJsonArray("sources").get(0).getAsJsonObject().getAsJsonObject("sourceKey").get("instanceId").getAsString());
    }
}
