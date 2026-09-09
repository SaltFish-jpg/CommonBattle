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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    }

    private static SceneAllianceAwarenessAgent createScene(Executor executor) {
        return createScene(executor, OwnerEventInterestControl.noop());
    }

    private static SceneAllianceAwarenessAgent createScene(Executor executor, OwnerEventInterestControl interests) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return new SceneAllianceAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-1"),
                interests
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
