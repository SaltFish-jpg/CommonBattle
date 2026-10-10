package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendOwnerKeyParser;
import com.commonbattle.game.social.FriendRelationAction;
import com.commonbattle.game.social.FriendSnapshot;
import com.commonbattle.game.social.FriendSnapshotReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneFriendAwarenessAgentTest {
    @Test
    void sceneAppliesFriendChangeOnlyForOnlinePlayer() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 1));
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(30003L, 20002L, FriendRelationAction.ADD, 1));
        executor.runNext();

        assertTrue(scene.friendsOf(10001L).orElseThrow().contains(20002L));
        assertTrue(scene.friendsOf(30003L).isEmpty());
    }

    @Test
    void directFriendEventHandlingCannotBypassSceneMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();

        assertThrows(IllegalStateException.class, () -> scene.handleFriendChanged(
                new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 1)
        ));
        assertTrue(scene.friendsOf(10001L).isEmpty());
    }

    @Test
    void directFriendSnapshotRefreshCannotBypassSceneMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();

        assertThrows(IllegalStateException.class,
                () -> scene.handleSnapshot(new FriendSnapshot(10001L, 1, Set.of(20002L))));
        assertTrue(scene.friendsOf(10001L).isEmpty());
    }

    @Test
    void duplicateFriendEventDoesNotOverwriteNewerSnapshot() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 1));
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.REMOVE, 2));
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 1));
        executor.runNext();

        assertFalse(scene.friendsOf(10001L).orElseThrow().contains(20002L));
        assertEquals(2, scene.revisionOf(10001L));
    }

    @Test
    void revisionGapMarksFriendViewStale() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        SceneFriendAwarenessAgent scene = createScene(executor, interests);

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 3));
        executor.runNext();

        ScenePlayerFriendView view = scene.friendsOf(10001L).orElseThrow();
        assertTrue(view.stale());
        assertEquals(3, view.revision());
        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), interests.repairs);
        assertEquals(new SceneProjectionStats(1, 1, 0, 1, 1, 0, 0, 1), scene.projectionStats());
    }

    @Test
    void enterAndLeaveDriveFriendOwnerInterestOnce() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        SceneFriendAwarenessAgent scene = createScene(executor, interests);

        scene.enter(10001L);
        executor.runNext();
        scene.enter(10001L);
        executor.runNext();
        scene.leave(10001L);
        executor.runNext();
        scene.leave(10001L);
        executor.runNext();

        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), interests.watched);
        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), interests.unwatched);
    }

    @Test
    void leaveClearsFriendRevisionSoDelayedEventCannotCreateFalseFreshCheckpoint() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 1));
        executor.runNext();
        scene.leave(10001L);
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 30003L, FriendRelationAction.ADD, 2));
        executor.runNext();

        assertTrue(scene.friendsOf(10001L).isEmpty());
        assertEquals(0, scene.revisionOf(10001L));
    }

    @Test
    void snapshotRefreshRunsInsideSceneMailboxAndClearsStaleView() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 3));
        executor.runNext();
        assertTrue(scene.friendsOf(10001L).orElseThrow().stale());

        scene.refresh(new FriendSnapshot(10001L, 3, Set.of(20002L, 30003L)));
        assertTrue(scene.friendsOf(10001L).orElseThrow().stale());
        executor.runNext();

        ScenePlayerFriendView view = scene.friendsOf(10001L).orElseThrow();
        assertFalse(view.stale());
        assertEquals(Set.of(20002L, 30003L), view.friends());
        assertEquals(3, view.revision());
        assertEquals(new SceneProjectionStats(1, 1, 0, 1, 1, 1, 0, 0), scene.projectionStats());
    }

    @Test
    void friendEventBusinessCallbackRunsAfterMailboxAppliesView() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(executor);
        AtomicReference<Optional<ScenePlayerFriendView>> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChangedAndReadView(
                new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 1),
                callback::set
        );

        assertNull(callback.get());

        executor.runNext();

        ScenePlayerFriendView view = callback.get().orElseThrow();
        assertTrue(view.contains(20002L));
        assertFalse(view.stale());
        assertEquals(1, view.revision());
    }

    @Test
    void friendEventMailboxCallbackReturnsStaleViewOnRevisionGap() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        SceneFriendAwarenessAgent scene = createScene(executor, interests);
        AtomicReference<Optional<ScenePlayerFriendView>> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChangedAndReadView(
                new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 3),
                callback::set
        );
        executor.runNext();

        ScenePlayerFriendView view = callback.get().orElseThrow();
        assertTrue(view.stale());
        assertEquals(3, view.revision());
        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), interests.repairs);
    }

    @Test
    void friendEventMailboxCallbackCanForceFreshSnapshotAfterRevisionGap() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        SceneFriendAwarenessAgent scene = createScene(
                executor,
                interests,
                playerId -> Optional.of(new FriendSnapshot(playerId, 3, Set.of(20002L, 30003L)))
        );
        AtomicReference<Optional<ScenePlayerFriendView>> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChangedAndReadFreshView(
                new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 3),
                callback::set
        );

        assertNull(callback.get());

        executor.runNext();

        ScenePlayerFriendView view = callback.get().orElseThrow();
        assertFalse(view.stale());
        assertEquals(3, view.revision());
        assertEquals(Set.of(20002L, 30003L), view.friends());
        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), interests.repairs);
        assertEquals(new SceneProjectionStats(1, 1, 0, 1, 1, 1, 0, 0,
                1, 0, 1, 0, 0), scene.projectionStats());
    }

    @Test
    void friendFreshReadResultExposesRefreshStatus() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(
                executor,
                OwnerEventInterestControl.noop(),
                playerId -> Optional.of(new FriendSnapshot(playerId, 3, Set.of(20002L, 30003L)))
        );
        AtomicReference<SceneFriendReadResult> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChangedAndReadFreshResult(
                new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 3),
                callback::set
        );
        executor.runNext();

        assertEquals(SceneFriendReadStatus.REFRESHED, callback.get().status());
        assertTrue(callback.get().fresh());
        assertEquals(Set.of(20002L, 30003L), callback.get().view().orElseThrow().friends());
    }

    @Test
    void friendFreshReadResultDistinguishesRemoteStaleAndRemoteMiss() {
        RecordingExecutor staleExecutor = new RecordingExecutor();
        SceneFriendAwarenessAgent staleScene = createScene(
                staleExecutor,
                OwnerEventInterestControl.noop(),
                playerId -> Optional.of(new FriendSnapshot(playerId, 2, Set.of(20002L)))
        );
        staleScene.enter(10001L);
        staleExecutor.runNext();
        AtomicReference<SceneFriendReadResult> stale = new AtomicReference<>();
        staleScene.onFriendChangedAndReadFreshResult(
                new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 3),
                stale::set
        );
        staleExecutor.runNext();

        RecordingExecutor missExecutor = new RecordingExecutor();
        SceneFriendAwarenessAgent missScene = createScene(missExecutor);
        missScene.enter(10001L);
        missExecutor.runNext();
        AtomicReference<SceneFriendReadResult> miss = new AtomicReference<>();
        missScene.onFriendChangedAndReadFreshResult(
                new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 3),
                miss::set
        );
        missExecutor.runNext();

        assertEquals(SceneFriendReadStatus.REMOTE_STALE, stale.get().status());
        assertFalse(stale.get().fresh());
        assertTrue(stale.get().view().isEmpty());
        assertEquals(SceneFriendReadStatus.REMOTE_MISS, miss.get().status());
        assertFalse(miss.get().fresh());
        assertTrue(miss.get().view().isEmpty());
    }

    @Test
    void directFreshFriendReadCannotRefreshOutsideSceneMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(
                executor,
                OwnerEventInterestControl.noop(),
                playerId -> Optional.of(new FriendSnapshot(playerId, 1, Set.of(20002L)))
        );

        scene.enter(10001L);
        executor.runNext();

        assertThrows(IllegalStateException.class, () -> scene.readFreshFriendsOf(10001L, 1));
        assertTrue(scene.friendsOf(10001L).isEmpty());
    }

    @Test
    void friendFreshReadReturnsEmptyWhenRemoteSnapshotIsOlderThanEvent() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(
                executor,
                OwnerEventInterestControl.noop(),
                playerId -> Optional.of(new FriendSnapshot(playerId, 2, Set.of(20002L)))
        );
        AtomicReference<Optional<ScenePlayerFriendView>> callback = new AtomicReference<>();

        scene.enter(10001L);
        executor.runNext();
        scene.onFriendChangedAndReadFreshView(
                new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 3),
                callback::set
        );
        executor.runNext();

        assertTrue(callback.get().isEmpty());
        assertTrue(scene.friendsOf(10001L).orElseThrow().stale());
        assertEquals(3, scene.friendsOf(10001L).orElseThrow().revision());
        assertEquals(new SceneProjectionStats(1, 1, 0, 1, 1, 0, 0, 1,
                1, 0, 0, 1, 0), scene.projectionStats());
    }

    @Test
    void ignoredSnapshotOfflineEventAndDuplicateEventAreCounted() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneFriendAwarenessAgent scene = createScene(executor);

        scene.refresh(new FriendSnapshot(10001L, 1, Set.of(20002L)));
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 1));
        executor.runNext();
        scene.enter(10001L);
        executor.runNext();
        scene.refresh(new FriendSnapshot(10001L, 3, Set.of(20002L)));
        executor.runNext();
        scene.refresh(new FriendSnapshot(10001L, 2, Set.of()));
        executor.runNext();
        scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 1));
        executor.runNext();

        assertEquals(new SceneProjectionStats(2, 0, 1, 0, 0, 1, 2, 0), scene.projectionStats());
    }

    private static SceneFriendAwarenessAgent createScene(Executor executor) {
        return createScene(executor, OwnerEventInterestControl.noop());
    }

    private static SceneFriendAwarenessAgent createScene(Executor executor, OwnerEventInterestControl interests) {
        return createScene(executor, interests, ignored -> Optional.empty());
    }

    private static SceneFriendAwarenessAgent createScene(
            Executor executor,
            OwnerEventInterestControl interests,
            FriendSnapshotReader snapshotReader
    ) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return new SceneFriendAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-friend"),
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
