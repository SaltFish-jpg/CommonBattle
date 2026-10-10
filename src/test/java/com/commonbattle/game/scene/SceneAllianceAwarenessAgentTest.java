package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import com.commonbattle.game.social.AllianceOwnerKeyParser;
import com.commonbattle.game.social.AllianceSnapshot;
import com.commonbattle.game.social.AllianceSnapshotReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneAllianceAwarenessAgentTest {
    @Test
    void sceneAppliesAllianceChangeOnlyForOnlinePlayer() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1));
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 20002L, AllianceMemberAction.JOIN, 2));
        executor.runNext();

        assertEquals(100, scene.allianceOf(10001L).orElseThrow().allianceId());
        assertFalse(scene.allianceOf(20002L).isPresent());
    }

    @Test
    void directAllianceEventHandlingCannotBypassSceneMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();

        assertThrows(IllegalStateException.class, () -> scene.handleAllianceChanged(
                new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1)
        ));
        assertTrue(scene.allianceOf(10001L).isEmpty());
    }

    @Test
    void directAllianceSnapshotRefreshCannotBypassSceneMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.watchAlliance(100);
        executor.runNext();

        assertThrows(IllegalStateException.class,
                () -> scene.handleSnapshot(new AllianceSnapshot(100, 1, java.util.Set.of(10001L))));
        assertTrue(scene.allianceOf(10001L).isEmpty());
    }

    @Test
    void duplicateAllianceEventDoesNotOverwriteNewerSnapshot() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1));
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.LEAVE, 2));
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1));
        executor.runNext();

        assertFalse(scene.allianceOf(10001L).isPresent());
        assertEquals(2, scene.revisionOf(100));
    }

    @Test
    void revisionGapMarksSceneSnapshotStale() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        SceneAllianceAwarenessAgent scene = createScene(executor, interests);

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3));
        executor.runNext();

        ScenePlayerAllianceView view = scene.allianceOf(10001L).orElseThrow();
        assertTrue(view.stale());
        assertEquals(3, view.revision());
        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100)), interests.repairs);
        assertEquals(new SceneProjectionStats(1, 1, 0, 1, 1, 0, 0, 1), scene.projectionStats());
    }

    @Test
    void offlineMemberRevisionGapStillRequestsAllianceSnapshotRepair() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        SceneAllianceAwarenessAgent scene = createScene(executor, interests);

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 20002L, AllianceMemberAction.JOIN, 3));
        executor.runNext();

        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100)), interests.repairs);
        assertFalse(scene.allianceOf(10001L).isPresent());
        assertEquals(3, scene.revisionOf(100));
        assertEquals(new SceneProjectionStats(1, 1, 0, 1, 1, 0, 0, 0), scene.projectionStats());
    }

    @Test
    void offlineMemberEventStillAdvancesAllianceRevisionCheckpoint() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 20002L, AllianceMemberAction.JOIN, 1));
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 2));
        executor.runNext();

        ScenePlayerAllianceView view = scene.allianceOf(10001L).orElseThrow();
        assertFalse(view.stale());
        assertEquals(2, view.revision());
    }

    @Test
    void watchAndUnwatchAllianceDriveOwnerInterestOnce() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        SceneAllianceAwarenessAgent scene = createScene(executor, interests);

        scene.watchAlliance(100);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.unwatchAlliance(100);
        executor.runNext();
        scene.unwatchAlliance(100);
        executor.runNext();

        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100)), interests.watched);
        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100)), interests.unwatched);
    }

    @Test
    void snapshotRefreshRunsInsideSceneMailboxAndClearsStaleView() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3));
        executor.runNext();
        assertTrue(scene.allianceOf(10001L).orElseThrow().stale());

        scene.refresh(new AllianceSnapshot(100, 3, java.util.Set.of(10001L)));
        assertTrue(scene.allianceOf(10001L).orElseThrow().stale());
        executor.runNext();

        ScenePlayerAllianceView view = scene.allianceOf(10001L).orElseThrow();
        assertFalse(view.stale());
        assertEquals(3, view.revision());
        assertEquals(new SceneProjectionStats(1, 1, 0, 1, 1, 1, 0, 0), scene.projectionStats());
    }

    @Test
    void allianceEventBusinessCallbackRunsAfterMailboxAppliesView() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);
        AtomicReference<Optional<ScenePlayerAllianceView>> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.onAllianceChangedAndReadView(
                new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1),
                callback::set
        );

        assertNull(callback.get());

        executor.runNext();

        ScenePlayerAllianceView view = callback.get().orElseThrow();
        assertEquals(100, view.allianceId());
        assertEquals(1, view.revision());
        assertFalse(view.stale());
    }

    @Test
    void allianceEventMailboxCallbackReturnsStaleMembershipOnRevisionGap() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        SceneAllianceAwarenessAgent scene = createScene(executor, interests);
        AtomicReference<AllianceMembershipDecision> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.onAllianceChangedAndReadMembership(
                new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3),
                callback::set
        );

        assertNull(callback.get());

        executor.runNext();

        assertEquals(AllianceMembershipDecision.STALE, callback.get());
        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100)), interests.repairs);
    }

    @Test
    void allianceEventMailboxCallbackCanForceFreshMembershipAfterRevisionGap() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        SceneAllianceAwarenessAgent scene = createScene(
                executor,
                interests,
                allianceId -> Optional.of(new AllianceSnapshot(allianceId, 3, java.util.Set.of(10001L, 20002L)))
        );
        AtomicReference<AllianceMembershipDecision> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.onAllianceChangedAndReadFreshMembership(
                new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3),
                callback::set
        );

        assertNull(callback.get());

        executor.runNext();

        assertEquals(AllianceMembershipDecision.MEMBER, callback.get());
        assertFalse(scene.allianceOf(10001L).orElseThrow().stale());
        assertEquals(3, scene.revisionOf(100));
        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100)), interests.repairs);
        assertEquals(new SceneProjectionStats(1, 1, 0, 1, 1, 1, 0, 0,
                1, 0, 1, 0, 0), scene.projectionStats());
    }

    @Test
    void allianceFreshMembershipResultExposesRefreshStatus() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(
                executor,
                OwnerEventInterestControl.noop(),
                allianceId -> Optional.of(new AllianceSnapshot(allianceId, 3, java.util.Set.of(10001L, 20002L)))
        );
        AtomicReference<SceneAllianceReadResult> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.onAllianceChangedAndReadFreshMembershipResult(
                new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3),
                callback::set
        );
        executor.runNext();

        assertEquals(SceneAllianceReadStatus.REFRESHED, callback.get().status());
        assertEquals(AllianceMembershipDecision.MEMBER, callback.get().membership());
        assertTrue(callback.get().fresh());
    }

    @Test
    void allianceFreshMembershipResultDistinguishesRemoteStaleAndRemoteMiss() {
        RecordingExecutor staleExecutor = new RecordingExecutor();
        SceneAllianceAwarenessAgent staleScene = createScene(
                staleExecutor,
                OwnerEventInterestControl.noop(),
                allianceId -> Optional.of(new AllianceSnapshot(allianceId, 2, java.util.Set.of(10001L)))
        );
        staleScene.enter(10001L);
        staleExecutor.runNext();
        staleScene.watchAlliance(100);
        staleExecutor.runNext();
        AtomicReference<SceneAllianceReadResult> stale = new AtomicReference<>();
        staleScene.onAllianceChangedAndReadFreshMembershipResult(
                new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3),
                stale::set
        );
        staleExecutor.runNext();

        RecordingExecutor missExecutor = new RecordingExecutor();
        SceneAllianceAwarenessAgent missScene = createScene(missExecutor);
        missScene.enter(10001L);
        missExecutor.runNext();
        missScene.watchAlliance(100);
        missExecutor.runNext();
        AtomicReference<SceneAllianceReadResult> miss = new AtomicReference<>();
        missScene.onAllianceChangedAndReadFreshMembershipResult(
                new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3),
                miss::set
        );
        missExecutor.runNext();

        assertEquals(SceneAllianceReadStatus.REMOTE_STALE, stale.get().status());
        assertEquals(AllianceMembershipDecision.STALE, stale.get().membership());
        assertFalse(stale.get().fresh());
        assertEquals(SceneAllianceReadStatus.REMOTE_MISS, miss.get().status());
        assertEquals(AllianceMembershipDecision.STALE, miss.get().membership());
        assertFalse(miss.get().fresh());
    }

    @Test
    void directFreshAllianceReadCannotRefreshOutsideSceneMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(
                executor,
                OwnerEventInterestControl.noop(),
                allianceId -> Optional.of(new AllianceSnapshot(allianceId, 1, java.util.Set.of(10001L)))
        );

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();

        assertThrows(IllegalStateException.class, () -> scene.readFreshMembershipOf(100, 10001L, 1));
        assertTrue(scene.allianceOf(10001L).isEmpty());
    }

    @Test
    void allianceFreshMembershipStaysStaleWhenRemoteSnapshotIsOlderThanEvent() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(
                executor,
                OwnerEventInterestControl.noop(),
                allianceId -> Optional.of(new AllianceSnapshot(allianceId, 2, java.util.Set.of(10001L)))
        );
        AtomicReference<AllianceMembershipDecision> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.onAllianceChangedAndReadFreshMembership(
                new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3),
                callback::set
        );
        executor.runNext();

        assertEquals(AllianceMembershipDecision.STALE, callback.get());
        assertTrue(scene.allianceOf(10001L).orElseThrow().stale());
        assertEquals(3, scene.revisionOf(100));
        assertEquals(new SceneProjectionStats(1, 1, 0, 1, 1, 0, 0, 1,
                1, 0, 0, 1, 0), scene.projectionStats());
    }

    @Test
    void ignoredSnapshotAndDuplicateEventAreCounted() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();
        scene.refresh(new AllianceSnapshot(100, 2, java.util.Set.of(10001L)));
        executor.runNext();
        scene.refresh(new AllianceSnapshot(100, 1, java.util.Set.of()));
        executor.runNext();
        scene.refresh(new AllianceSnapshot(200, 1, java.util.Set.of(10001L)));
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1));
        executor.runNext();

        assertEquals(new SceneProjectionStats(1, 0, 1, 0, 0, 1, 2, 0), scene.projectionStats());
    }

    @Test
    void cachedAllianceSnapshotRestoresViewWhenPlayerEntersLater() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.watchAlliance(100);
        executor.runNext();
        scene.refresh(new AllianceSnapshot(100, 2, java.util.Set.of(10001L, 20002L)));
        executor.runNext();
        scene.enter(20002L);
        executor.runNext();

        ScenePlayerAllianceView view = scene.allianceOf(20002L).orElseThrow();
        assertEquals(100, view.allianceId());
        assertEquals(2, view.revision());
        assertFalse(view.stale());
    }

    @Test
    void unwatchAllianceDropsCachedSnapshotRevisionAndDerivedViews() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.watchAlliance(100);
        executor.runNext();
        scene.refresh(new AllianceSnapshot(100, 2, java.util.Set.of(10001L)));
        executor.runNext();
        scene.enter(10001L);
        executor.runNext();
        scene.unwatchAlliance(100);
        executor.runNext();

        assertFalse(scene.allianceOf(10001L).isPresent());
        assertEquals(0, scene.revisionOf(100));
    }

    private static SceneAllianceAwarenessAgent createScene(Executor executor) {
        return createScene(executor, OwnerEventInterestControl.noop());
    }

    private static SceneAllianceAwarenessAgent createScene(Executor executor, OwnerEventInterestControl interests) {
        return createScene(executor, interests, ignored -> Optional.empty());
    }

    private static SceneAllianceAwarenessAgent createScene(
            Executor executor,
            OwnerEventInterestControl interests,
            AllianceSnapshotReader snapshotReader
    ) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return new SceneAllianceAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-1"),
                interests,
                snapshotReader
        );
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class RecordingOwnerInterests implements OwnerEventInterestControl {
        private final List<String> watched = new ArrayList<>();
        private final List<String> unwatched = new ArrayList<>();
        private final List<String> repairs = new ArrayList<>();

        @Override
        public void watchOwner(String ownerKey) {
            watched.add(ownerKey);
        }

        @Override
        public void unwatchOwner(String ownerKey) {
            unwatched.add(ownerKey);
        }

        @Override
        public void requestRepairOwner(String ownerKey) {
            repairs.add(ownerKey);
        }
    }
}
