package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void gapMarksSceneDomainViewStale() {
        RecordingExecutor executor = new RecordingExecutor();
        ScenePlayerDomainEventAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onPlayerDomainEvent(event(10001L, 3, 1));
        executor.runNext();

        assertEquals(1, scene.stageClears(10001L).orElseThrow());
        assertTrue(scene.stale(10001L));
        assertEquals(3, scene.revisionOf(10001L));
    }

    private static ScenePlayerDomainEventAgent createScene(Executor executor) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return new ScenePlayerDomainEventAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-domain-events")
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
}
