package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SceneServiceStrategyTest {
    @Test
    void smallSceneServiceUsesOneActorPerScene() {
        try (ActorSystem actors = new ActorSystem(1)) {
            MultiSmallSceneService service = MultiSmallSceneService.create(
                    actors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9100),
                    200
            );

            ScenePlacement first = service.place("room-1", 0, 0);
            ScenePlacement same = service.place("room-1", 9, 9);
            ScenePlacement other = service.place("room-2", 0, 0);

            assertEquals(first.actor(), same.actor());
            assertNotEquals(first.actor(), other.actor());
            assertEquals(SceneHostingMode.MULTI_SMALL_SCENE.name(), service.descriptor().metadata("scene.mode"));
            assertEquals(1, service.descriptor().protocolVersion());
            assertEquals("1", service.descriptor().metadata(ServiceMetadata.PROTOCOL_VERSION));
            assertEquals("2", service.descriptor().metadata(ServiceMetadata.LOAD_USED));
            assertEquals("200", service.descriptor().metadata(ServiceMetadata.LOAD_CAPACITY));
        }
    }

    @Test
    void largeSceneServiceSplitsChunksAcrossShardActors() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8
            );

            ScenePlacement first = service.place("world-1", 10, 20);
            ScenePlacement same = service.place("world-1", 10, 20);

            assertEquals(first, same);
            assertEquals(8, first.shardCount());
            assertEquals(SceneHostingMode.LARGE_SCENE_SHARD.name(), service.descriptor().metadata("scene.mode"));
            assertEquals(1, service.descriptor().protocolVersion());
            assertEquals("0", service.descriptor().metadata(ServiceMetadata.LOAD_USED));
            assertEquals("8", service.descriptor().metadata(ServiceMetadata.LOAD_CAPACITY));
        }
    }

    @Test
    void profileAwareWrapperDrivesProfileInterestOnEnterAndLeave() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingInterestControl interests = new RecordingInterestControl();
        try (ActorSystem actors = new ActorSystem(executor, 64)) {
            MultiSmallSceneService delegate = MultiSmallSceneService.create(
                    actors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9100),
                    200
            );
            SceneProfileAwarenessAgent profiles = new SceneProfileAwarenessAgent(
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    actors.actor("scene-profile"),
                    interests
            );
            ProfileAwareSceneService service = new ProfileAwareSceneService(delegate, profiles);

            ScenePlacement placement = service.enter(10001L, "room-1", 0, 0);
            executor.runNext();
            boolean left = service.leave(10001L, "room-1");
            executor.runNext();

            assertEquals("room-1", placement.sceneId());
            assertEquals(true, left);
            assertEquals(List.of(10001L), interests.watched);
            assertEquals(List.of(10001L), interests.unwatched);
        }
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

    private static final class RecordingInterestControl implements ProfileInterestControl {
        private final List<Long> watched = new ArrayList<>();
        private final List<Long> unwatched = new ArrayList<>();

        @Override
        public void watch(long playerId) {
            watched.add(playerId);
        }

        @Override
        public void unwatch(long playerId) {
            unwatched.add(playerId);
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
