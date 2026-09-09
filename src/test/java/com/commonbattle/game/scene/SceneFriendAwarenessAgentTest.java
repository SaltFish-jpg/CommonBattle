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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    }

    private static SceneFriendAwarenessAgent createScene(Executor executor) {
        return createScene(executor, OwnerEventInterestControl.noop());
    }

    private static SceneFriendAwarenessAgent createScene(Executor executor, OwnerEventInterestControl interests) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return new SceneFriendAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-friend"),
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
