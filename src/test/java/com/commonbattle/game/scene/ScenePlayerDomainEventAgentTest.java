package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.player.event.BagChangedEvent;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.player.event.PlayerDomainProjectionSnapshot;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.player.PlayerBusinessOperations;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenePlayerDomainEventAgentTest {
    @Test
    void sceneConsumesPlayerDomainEventOnlyForOnlinePlayer() {
        RecordingExecutor executor = new RecordingExecutor();
        ScenePlayerDomainEventAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onPlayerDomainEvent(event(10001L, 1, 1));
        executor.runNext();
        scene.onPlayerDomainEvent(event(20002L, 1, 1));
        executor.runNext();

        assertEquals(1, scene.stageClears(10001L).orElseThrow());
        assertFalse(scene.stageClears(20002L).isPresent());
    }

    @Test
    void directDomainEventHandlingCannotBypassSceneMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ScenePlayerDomainEventAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();

        assertThrows(IllegalStateException.class, () -> scene.handlePlayerDomainEvent(event(10001L, 1, 1)));
        assertTrue(scene.stageClears(10001L).isEmpty());
    }

    @Test
    void directSnapshotRepairCannotBypassSceneMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ScenePlayerDomainEventAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();

        assertThrows(IllegalStateException.class, () -> scene.handleSnapshot(new PlayerDomainProjectionSnapshot(
                10001L,
                1,
                Map.of("forest-1", 2),
                Map.of("gold", 90)
        )));
        assertTrue(scene.stageClears(10001L).isEmpty());
    }

    @Test
    void duplicateEventDoesNotMutateSceneViewTwice() {
        RecordingExecutor executor = new RecordingExecutor();
        ScenePlayerDomainEventAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onPlayerDomainEvent(event(10001L, 1, 1));
        executor.runNext();
        scene.onPlayerDomainEvent(event(10001L, 1, 1));
        executor.runNext();

        assertEquals(1, scene.stageClears(10001L).orElseThrow());
        assertEquals(1, scene.revisionOf(10001L));
    }

    @Test
    void bagChangedEventUpdatesSceneBagProjectionForOnlinePlayer() {
        RecordingExecutor executor = new RecordingExecutor();
        ScenePlayerDomainEventAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onPlayerDomainEvent(bagEvent(10001L, 1, "gold", 0, 30));
        executor.runNext();
        scene.onPlayerDomainEvent(bagEvent(10001L, 2, "gold", 30, 10));
        executor.runNext();

        assertEquals(10, scene.bagCount(10001L, "gold").orElseThrow());
        assertEquals(2, scene.revisionOf(10001L));
    }

    @Test
    void duplicateBagChangedEventDoesNotMutateProjectionTwice() {
        RecordingExecutor executor = new RecordingExecutor();
        ScenePlayerDomainEventAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onPlayerDomainEvent(bagEvent(10001L, 1, "gold", 0, 30));
        executor.runNext();
        scene.onPlayerDomainEvent(bagEvent(10001L, 1, "gold", 0, 30));
        executor.runNext();

        assertEquals(30, scene.bagCount(10001L, "gold").orElseThrow());
        assertEquals(1, scene.revisionOf(10001L));
    }

    @Test
    void gapMarksSceneDomainViewStale() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        ScenePlayerDomainEventAgent scene = createScene(executor, interests);

        scene.enter(10001L);
        executor.runNext();
        scene.onPlayerDomainEvent(event(10001L, 3, 1));
        executor.runNext();

        assertEquals(1, scene.stageClears(10001L).orElseThrow());
        assertTrue(scene.stale(10001L));
        assertEquals(3, scene.revisionOf(10001L));
        assertEquals(List.of(PlayerDomainVersionedEvent.ownerKey(10001L)), interests.repairs);
    }

    @Test
    void snapshotRefreshRunsInsideSceneMailboxAndClearsStaleProjection() {
        RecordingExecutor executor = new RecordingExecutor();
        ScenePlayerDomainEventAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onPlayerDomainEvent(event(10001L, 3, 1));
        executor.runNext();

        scene.refresh(new PlayerDomainProjectionSnapshot(
                10001L,
                3,
                Map.of("forest-1", 2, "cave-1", 1),
                Map.of("gold", 90, "gem", 5)
        ));

        assertEquals(1, scene.stageClears(10001L).orElseThrow());
        assertTrue(scene.stale(10001L));

        executor.runNext();

        assertEquals(3, scene.stageClears(10001L).orElseThrow());
        assertEquals(90, scene.bagCount(10001L, "gold").orElseThrow());
        assertEquals(5, scene.bagCount(10001L, "gem").orElseThrow());
        assertEquals(3, scene.revisionOf(10001L));
        assertFalse(scene.stale(10001L));
    }

    @Test
    void enterAndLeaveDrivePlayerDomainOwnerInterestOnce() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests interests = new RecordingOwnerInterests();
        ScenePlayerDomainEventAgent scene = createScene(executor, interests);

        scene.enter(10001L);
        executor.runNext();
        scene.enter(10001L);
        executor.runNext();
        scene.leave(10001L);
        executor.runNext();
        scene.leave(10001L);
        executor.runNext();

        assertEquals(List.of(PlayerDomainVersionedEvent.ownerKey(10001L)), interests.watched);
        assertEquals(List.of(PlayerDomainVersionedEvent.ownerKey(10001L)), interests.unwatched);
    }

    private static ScenePlayerDomainEventAgent createScene(Executor executor) {
        return createScene(executor, OwnerEventInterestControl.noop());
    }

    private static ScenePlayerDomainEventAgent createScene(Executor executor, OwnerEventInterestControl interests) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return new ScenePlayerDomainEventAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-domain-events"),
                new PlayerDomainEventProcessor(),
                interests
        );
    }

    private static PlayerDomainVersionedEvent event(long playerId, long revision, int delta) {
        return new PlayerDomainVersionedEvent(
                playerId,
                BattleStageClearedEvent.TYPE,
                "forest-1",
                delta,
                revision,
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }

    private static PlayerDomainVersionedEvent bagEvent(
            long playerId,
            long revision,
            String itemId,
            int before,
            int after
    ) {
        return new PlayerDomainVersionedEvent(
                playerId,
                BagChangedEvent.TYPE,
                PlayerBusinessOperations.BAG_CONSUME_ITEM,
                Math.abs(after - before),
                revision,
                Instant.parse("2026-09-01T00:00:00Z"),
                Map.of(
                        "reason", PlayerBusinessOperations.BAG_CONSUME_ITEM,
                        "changeCount", "1",
                        "change.0.itemId", itemId,
                        "change.0.before", Integer.toString(before),
                        "change.0.after", Integer.toString(after)
                )
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
