package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.game.scene.SceneRuntimeStats;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.observability.RuntimeHealthPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            assertEquals("0", service.descriptor().metadata(SceneRuntimeMetadata.ACTIVE_SCENES));
            assertEquals("0", service.descriptor().metadata(SceneRuntimeMetadata.ACTIVE_PLAYERS));
        }
    }

    @Test
    void smallSceneServiceTracksActivePlayersByScene() {
        try (ActorSystem actors = new ActorSystem(1)) {
            MultiSmallSceneService service = MultiSmallSceneService.create(
                    actors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9100),
                    200
            );

            service.enter(10001L, "room-1", 0, 0);
            service.enter(10002L, "room-1", 1, 1);
            service.enter(10003L, "room-2", 0, 0);

            assertEquals(new SceneRuntimeStats(2, 3, 0, 3), service.stats());
            assertEquals("2", service.descriptor().metadata(SceneRuntimeMetadata.ACTIVE_SCENES));
            assertEquals("3", service.descriptor().metadata(SceneRuntimeMetadata.ACTIVE_PLAYERS));

            assertEquals(true, service.leave(10003L, "room-2"));

            assertEquals(new SceneRuntimeStats(1, 2, 0, 2), service.stats());
            assertEquals("1", service.descriptor().metadata(SceneRuntimeMetadata.ACTIVE_SCENES));
            assertEquals("2", service.descriptor().metadata(SceneRuntimeMetadata.ACTIVE_PLAYERS));
        }
    }

    @Test
    void smallSceneServiceRejectsNewSceneWhenCapacityIsFull() {
        try (ActorSystem actors = new ActorSystem(1)) {
            MultiSmallSceneService service = MultiSmallSceneService.create(
                    actors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9100),
                    1
            );

            service.enter(10001L, "room-1", 0, 0);
            service.enter(10002L, "room-1", 1, 1);

            assertThrows(SceneCapacityExceededException.class, () -> service.enter(10003L, "room-2", 0, 0));

            assertEquals(true, service.leave(10001L, "room-1"));
            assertThrows(SceneCapacityExceededException.class, () -> service.enter(10003L, "room-2", 0, 0));

            assertEquals(true, service.leave(10002L, "room-1"));
            service.enter(10003L, "room-2", 0, 0);

            assertEquals(new SceneRuntimeStats(1, 1, 0, 1), service.stats());
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
    void largeSceneServiceTracksActivePlayersAndShardHotspot() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8
            );

            service.enter(10001L, "world-1", 0, 0);
            service.enter(10002L, "world-1", 0, 0);
            service.enter(10003L, "world-1", 1, 0);

            assertEquals(new SceneRuntimeStats(1, 3, 8, 2), service.stats());
            assertEquals("3", service.descriptor().metadata(ServiceMetadata.LOAD_USED));
            assertEquals("3", service.descriptor().metadata(SceneRuntimeMetadata.ACTIVE_PLAYERS));
            assertEquals("2", service.descriptor().metadata(SceneRuntimeMetadata.MAX_SHARD_PLAYERS));

            assertEquals(true, service.leave(10001L, "world-1"));

            assertEquals(new SceneRuntimeStats(1, 2, 8, 1), service.stats());
        }
    }

    @Test
    void sceneRuntimeMetadataMarksCapacityDegradedByPolicy() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8
            );
            service.enter(10001L, "world-1", 0, 0);
            service.enter(10002L, "world-1", 0, 0);

            ServiceDescriptor published = SceneRuntimeMetadata.apply(
                    service.descriptor(),
                    service.stats(),
                    new RuntimeHealthPolicy(10_000, 0, 300_000, 0, 0, 1)
            );

            assertEquals(SceneRuntimeMetadata.CAPACITY_DEGRADED,
                    published.metadata(SceneRuntimeMetadata.CAPACITY_STATUS));
            assertEquals(SceneRuntimeMetadata.MAX_SHARD_PLAYERS,
                    published.metadata(SceneRuntimeMetadata.CAPACITY_REASON));
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

    @Test
    void profileAwareWrapperCanDriveDomainEventInterestOnEnterAndLeave() {
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
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            SceneProfileAwarenessAgent profiles = new SceneProfileAwarenessAgent(
                    messages,
                    actors.actor("scene-profile"),
                    interests
            );
            ScenePlayerDomainEventAgent domainEvents = new ScenePlayerDomainEventAgent(
                    messages,
                    actors.actor("scene-domain-events")
            );
            ProfileAwareSceneService service = new ProfileAwareSceneService(delegate, profiles, domainEvents);

            service.enter(10001L, "room-1", 0, 0);
            executor.runNext();
            executor.runNext();
            service.leave(10001L, "room-1");
            executor.runNext();
            executor.runNext();

            assertEquals(List.of(10001L), interests.watched);
            assertEquals(List.of(10001L), interests.unwatched);
            assertEquals(false, domainEvents.stageClears(10001L).isPresent());
            assertEquals(true, service.domainEvents().isPresent());
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
