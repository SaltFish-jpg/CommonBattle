package com.commonbattle.example.cross.scene;




import com.commonbattle.battle.command.Command;
import com.commonbattle.battle.event.Event;
import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorSystemClosedException;
import com.commonbattle.actor.backpressure.ActorMailboxPressureAdmissionController;
import com.commonbattle.actor.backpressure.ActorMailboxPressurePolicy;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.scene.SceneRuntimeStats;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendOwnerKeyParser;
import com.commonbattle.game.social.FriendRelationAction;
import com.commonbattle.observability.RuntimeHealthPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertEquals("1", service.descriptor().metadata("scene.shard.chunk.size"));
        }
    }

    @Test
    void smallSceneAsyncEnterMutatesOnlyAfterSceneMailboxRuns() {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64)) {
            MultiSmallSceneService service = MultiSmallSceneService.create(
                    actors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9100),
                    200
            );
            AtomicReference<ScenePlacement> placement = new AtomicReference<>();

            service.enterAsync(10001L, "room-1", 0, 0, placement::set, error -> {
                throw new AssertionError(error);
            });

            assertEquals(new SceneRuntimeStats(0, 0, 0, 0), service.stats());
            assertEquals(null, placement.get());

            executor.runAll();

            assertEquals("room-1", placement.get().sceneId());
            assertEquals(new SceneRuntimeStats(1, 1, 0, 1), service.stats());
        }
    }

    @Test
    void smallSceneMigrationExportRequiresSceneMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64)) {
            MultiSmallSceneService service = MultiSmallSceneService.create(
                    actors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9100),
                    200
            );
            ScenePlacement placement = service.enter(10001L, "room-1", 0, 0);
            AtomicReference<SmallSceneAgentSnapshot> snapshot = new AtomicReference<>();

            assertThrows(IllegalStateException.class,
                    () -> service.exportForMigrationInCurrentMailbox("room-1"));

            actors.send(placement.actor(),
                    ignored -> snapshot.set(service.exportForMigrationInCurrentMailbox("room-1")));
            executor.runAll();

            assertEquals(Set.of(10001L), snapshot.get().players());
        }
    }

    @Test
    void smallSceneAsyncEnterReportsMailboxDeliveryFailureByCallback() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        MultiSmallSceneService service = MultiSmallSceneService.create(
                actors,
                "r1",
                "scene-small-1",
                new ServiceEndpoint("127.0.0.1", 9100),
                200
        );
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ScenePlacement> placement = new AtomicReference<>();

        actors.close();
        service.enterAsync(10001L, "room-1", 0, 0, placement::set, failure::set);

        assertEquals(null, placement.get());
        assertEquals(ActorSystemClosedException.class, failure.get().getClass());
        assertEquals(new SceneRuntimeStats(0, 0, 0, 0), service.stats());
    }

    @Test
    void largeSceneShardPlannerGroupsChunksIntoStableCells() {
        LargeSceneShardPlanner planner = new LargeSceneShardPlanner("world-1", 8, 4);

        LargeSceneShardRoute origin = planner.route("world-1", 0, 0);
        LargeSceneShardRoute sameCell = planner.route("world-1", 3, 3);
        LargeSceneShardRoute nextCell = planner.route("world-1", 4, 0);
        LargeSceneShardRoute negativeCell = planner.route("world-1", -1, -1);

        assertEquals(origin.shardIndex(), sameCell.shardIndex());
        assertNotEquals(origin.shardIndex(), nextCell.shardIndex());
        assertEquals(-1, negativeCell.cellX());
        assertEquals(-1, negativeCell.cellY());
        assertEquals(new LargeSceneShardAgentKey("world-1", origin.shardIndex()), origin.agentKey());
    }

    @Test
    void largeSceneServiceCanUseConfiguredShardPlanner() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC()
            );

            ScenePlacement first = service.enter(10001L, "world-1", 0, 0);
            ScenePlacement sameCell = service.enter(10002L, "world-1", 3, 3);

            assertEquals(first.actor(), sameCell.actor());
            assertEquals(4, service.planner().chunkSize());
            assertEquals("4", service.descriptor().metadata("scene.shard.chunk.size"));
            assertEquals(2, service.shardPlayers(first.shardIndex()).size());
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
    void largeSceneAsyncEnterAndMoveMutateOnlyAfterShardMailboxRuns() {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC()
            );
            AtomicReference<ScenePlacement> entered = new AtomicReference<>();
            AtomicReference<SceneMoveOutcome> moved = new AtomicReference<>();

            service.enterAsync(10001L, "world-1", 0, 0, entered::set, error -> {
                throw new AssertionError(error);
            });

            assertEquals(new SceneRuntimeStats(0, 0, 8, 0), service.stats());
            assertEquals(null, entered.get());

            executor.runAll();

            assertEquals("world-1", entered.get().sceneId());
            assertEquals(new SceneRuntimeStats(1, 1, 8, 1), service.stats());

            service.moveAsync(10001L, "world-1", 4, 0, moved::set, error -> {
                throw new AssertionError(error);
            });

            assertEquals(null, moved.get());
            executor.runAll();

            assertEquals(true, moved.get().moved());
            assertEquals(entered.get().shardIndex(), moved.get().previousShardIndex());
            assertNotEquals(entered.get().shardIndex(), moved.get().shardIndex());
        }
    }

    @Test
    void largeSceneMigrationExportRequiresShardMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC()
            );
            ScenePlacement placement = service.enter(10001L, "world-1", 0, 0);
            AtomicReference<LargeSceneShardAgentSnapshot> snapshot = new AtomicReference<>();

            assertThrows(IllegalStateException.class,
                    () -> service.exportShardForMigrationInCurrentMailbox(placement.shardIndex()));

            actors.send(placement.actor(),
                    ignored -> snapshot.set(service.exportShardForMigrationInCurrentMailbox(placement.shardIndex())));
            executor.runAll();

            assertEquals(Set.of(10001L), snapshot.get().players());
        }
    }

    @Test
    void largeSceneMoveKeepsPlayerInSameShardWhenChunkStaysInSameCell() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC()
            );
            ScenePlacement placement = service.enter(10001L, "world-1", 0, 0);

            SceneMoveOutcome moved = service.move(10001L, "world-1", 3, 3);

            assertEquals(true, moved.moved());
            assertEquals(placement.shardIndex(), moved.previousShardIndex());
            assertEquals(placement.shardIndex(), moved.shardIndex());
            assertEquals(Set.of(10001L), service.shardPlayers(placement.shardIndex()));
            assertEquals(new SceneRuntimeStats(1, 1, 8, 1), service.stats());
        }
    }

    @Test
    void largeSceneEnterBuildsShardAoiLinksForNearbyPlayers() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC()
            );

            service.enter(10001L, "world-1", 0, 0);
            service.enter(20002L, "world-1", 1, 0);

            assertEquals(List.of(20002L), service.visiblePlayers(10001L));
            assertEquals(List.of(10001L), service.visiblePlayers(20002L));
        }
    }

    @Test
    void largeSceneMoveReportsAoiDeltaInsideShard() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC()
            );
            service.enter(10001L, "world-1", 0, 0);
            service.enter(20002L, "world-1", 1, 0);

            SceneMoveOutcome moved = service.move(20002L, "world-1", 8, 0);

            assertEquals(true, moved.moved());
            assertEquals(List.of(10001L), moved.aoi().visibleLeave());
            assertEquals(List.of(10001L), moved.aoi().observerLeave());
            assertEquals(List.of(), service.visiblePlayers(10001L));
            assertEquals(List.of(), service.visiblePlayers(20002L));
        }
    }

    @Test
    void largeScenePublishesBroadcastsFromAoiChangesInsideShardMailbox() {
        InMemorySceneBroadcastSink broadcasts = new InMemorySceneBroadcastSink();
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC(),
                    broadcasts
            );

            service.enter(10001L, "world-1", 0, 0);
            service.enter(20002L, "world-1", 1, 0);
            service.move(20002L, "world-1", 2, 0);
            service.move(20002L, "world-1", 8, 0);

            List<SceneBroadcastEnvelope> envelopes = broadcasts.envelopes();
            assertEquals(3, envelopes.size());
            assertEquals(Set.of(10001L), envelopes.get(0).recipients());
            assertEquals(SceneBroadcastEventType.ENTER_VIEW, envelopes.get(0).event().type());
            assertEquals(20002L, envelopes.get(0).event().subjectPlayerId());
            assertEquals(Set.of(10001L), envelopes.get(1).recipients());
            assertEquals(SceneBroadcastEventType.PLAYER_MOVED, envelopes.get(1).event().type());
            assertEquals(2, envelopes.get(1).event().chunkX());
            assertEquals(Set.of(10001L), envelopes.get(2).recipients());
            assertEquals(SceneBroadcastEventType.LEAVE_VIEW, envelopes.get(2).event().type());
        }
    }

    @Test
    void largeSceneLeavePublishesLeaveViewBroadcast() {
        InMemorySceneBroadcastSink broadcasts = new InMemorySceneBroadcastSink();
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC(),
                    broadcasts
            );
            service.enter(10001L, "world-1", 0, 0);
            service.enter(20002L, "world-1", 1, 0);

            service.leave(20002L, "world-1");

            SceneBroadcastEnvelope envelope = broadcasts.envelopes().getLast();
            assertEquals(Set.of(10001L), envelope.recipients());
            assertEquals(SceneBroadcastEventType.LEAVE_VIEW, envelope.event().type());
            assertEquals(20002L, envelope.event().subjectPlayerId());
        }
    }

    @Test
    void largeSceneDamageBroadcastsToParticipantsAndVisiblePlayers() {
        InMemorySceneBroadcastSink broadcasts = new InMemorySceneBroadcastSink();
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC(),
                    broadcasts
            );
            service.enter(10001L, "world-1", 0, 0);
            service.enter(20002L, "world-1", 1, 0);
            service.enter(30003L, "world-1", 2, 0);
            service.registerCombatant(10001L, 100);
            service.registerCombatant(20002L, 80);

            SceneCombatDamageOutcome outcome = service.applyDamage(10001L, 20002L, 35);

            assertEquals(true, outcome.applied());
            assertEquals(80, outcome.hpBefore());
            assertEquals(45, outcome.hpAfter());
            assertEquals(45, service.hitPoints(20002L).orElseThrow());
            SceneBroadcastEnvelope damage = broadcasts.envelopes().getLast();
            assertEquals(Set.of(10001L, 20002L, 30003L), damage.recipients());
            assertEquals(SceneBroadcastEventType.COMBAT_DAMAGE, damage.event().type());
            assertEquals(10001L, damage.event().sourcePlayerId());
            assertEquals(20002L, damage.event().subjectPlayerId());
            assertEquals(35, damage.event().value());
            assertEquals(45, damage.event().hpAfter());
        }
    }

    @Test
    void largeSceneDamageRejectsTargetsOutsideCurrentShard() {
        InMemorySceneBroadcastSink broadcasts = new InMemorySceneBroadcastSink();
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC(),
                    broadcasts
            );
            service.enter(10001L, "world-1", 0, 0);
            service.enter(20002L, "world-1", 4, 0);
            service.registerCombatant(10001L, 100);
            service.registerCombatant(20002L, 80);
            int beforeBroadcasts = broadcasts.envelopes().size();

            SceneCombatDamageOutcome outcome = service.applyDamage(10001L, 20002L, 35);

            assertEquals(false, outcome.applied());
            assertEquals(80, service.hitPoints(20002L).orElseThrow());
            assertEquals(beforeBroadcasts, broadcasts.envelopes().size());
        }
    }

    @Test
    void largeSceneMoveTransfersPlayerBetweenShardActors() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8,
                    4,
                    Clock.systemUTC()
            );
            ScenePlacement previous = service.enter(10001L, "world-1", 0, 0);
            ScenePlacement next = service.place("world-1", 4, 0);

            SceneMoveOutcome moved = service.move(10001L, "world-1", 4, 0);

            assertEquals(true, moved.moved());
            assertEquals(previous.shardIndex(), moved.previousShardIndex());
            assertEquals(next.shardIndex(), moved.shardIndex());
            assertEquals(Set.of(), service.shardPlayers(previous.shardIndex()));
            assertEquals(Set.of(10001L), service.shardPlayers(next.shardIndex()));
            assertEquals(List.of(), moved.aoi().visibleEnter());
            assertEquals(List.of(), moved.aoi().visibleLeave());
            assertEquals(new SceneRuntimeStats(1, 1, 8, 1), service.stats());
        }
    }

    @Test
    void largeSceneMoveRejectsPlayerThatHasNotEntered() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8
            );

            SceneMoveOutcome moved = service.move(10001L, "world-1", 4, 0);

            assertEquals(false, moved.moved());
            assertEquals(-1, moved.previousShardIndex());
            assertEquals(-1, moved.shardIndex());
            assertEquals(new SceneRuntimeStats(0, 0, 8, 0), service.stats());
        }
    }

    @Test
    void smallSceneMoveRequiresExistingPlayerAndDoesNotChangeActor() {
        try (ActorSystem actors = new ActorSystem(1)) {
            MultiSmallSceneService service = MultiSmallSceneService.create(
                    actors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9100),
                    200
            );
            service.enter(10001L, "room-1", 0, 0);

            SceneMoveOutcome moved = service.move(10001L, "room-1", 99, -20);
            SceneMoveOutcome missing = service.move(20002L, "room-1", 99, -20);

            assertEquals(true, moved.moved());
            assertEquals(0, moved.shardIndex());
            assertEquals(false, missing.moved());
            assertEquals(new SceneRuntimeStats(1, 1, 0, 1), service.stats());
        }
    }

    @Test
    void largeSceneShardTicksRunAfterShardMailboxIsDrained() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        Clock clock = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
        try (ActorSystem actors = new ActorSystem(executor, 64);
             ActorScheduleRegistry schedules = new ActorScheduleRegistry(actors)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    2,
                    clock
            );
            service.enter(10001L, "world-1", 0, 0);
            service.enter(10002L, "world-1", 0, 0);
            service.enter(10003L, "world-1", 1, 0);
            List<SceneShardTickContext> ticks = new ArrayList<>();

            service.scheduleShardTicks(schedules, Duration.ofMillis(1), Duration.ofDays(1), ticks::add);

            awaitQueued(executor, 2);
            assertEquals(List.of(), ticks);

            executor.runAll();

            List<SceneShardTickContext> ordered = ticks.stream()
                    .sorted(Comparator.comparingInt(SceneShardTickContext::shardIndex))
                    .toList();
            assertEquals(2, ordered.size());
            assertEquals(List.of(10001L, 10002L), ordered.get(0).players().stream().sorted().toList());
            assertEquals(List.of(10003L), ordered.get(1).players().stream().sorted().toList());
            assertEquals(2, service.tickStats().completedTicks());
            assertEquals(2, schedules.stats().deliveredTimerMessages());
        }
    }

    @Test
    void largeSceneShardTickSchedulesAreReplacedByBusinessKey() {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64);
             ActorScheduleRegistry schedules = new ActorScheduleRegistry(actors)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    2
            );

            service.scheduleShardTicks(schedules, Duration.ofHours(1), Duration.ofHours(1), ignored -> {
            });
            service.scheduleShardTicks(schedules, Duration.ofHours(2), Duration.ofHours(1), ignored -> {
            });

            assertEquals(4, service.tickStats().scheduledTickJobs());
            assertEquals(2, schedules.stats().activeJobs());
            assertEquals(4, schedules.stats().scheduledJobs());
            assertEquals(2, schedules.stats().cancelledJobs());
        }
    }

    @Test
    void largeSceneShardTickFailuresAreCountedInsideActorBoundary() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64);
             ActorScheduleRegistry schedules = new ActorScheduleRegistry(actors)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    2
            );

            service.scheduleShardTicks(schedules, Duration.ofMillis(1), Duration.ofDays(1), ignored -> {
                throw new IllegalStateException("tick failed");
            });

            awaitQueued(executor, 2);
            executor.runAll();

            assertEquals(0, service.tickStats().completedTicks());
            assertEquals(2, service.tickStats().failedTicks());
            assertEquals(2, actors.stats().failedTasks());
        }
    }

    @Test
    void sceneMailboxPressureGateRejectsHotShardBeforeEnterMutatesState() {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    2
            );
            ActorMailboxPressureAdmissionController pressure = new ActorMailboxPressureAdmissionController(
                    (target, operation) -> AdmissionDecision.accept(),
                    actors,
                    new ActorMailboxPressurePolicy(true, 1, 0, Duration.ofMillis(50)),
                    SceneMailboxPressureGate::actorIdOf
            );
            SceneMailboxPressureGate gate = new SceneMailboxPressureGate(service, pressure);
            actors.send(service.place("world-1", 0, 0).actor(), ignored -> {
            });

            SceneMailboxPressureGate.SceneAdmission admission = gate.admitEnter("world-1", 0, 0);

            assertFalse(admission.accepted());
            assertEquals("mailbox_pressure:target", admission.decision().reason());
            assertEquals(1, pressure.mailboxPressureStats().pressureRejected());
            assertEquals(new SceneRuntimeStats(0, 0, 2, 0), service.stats());
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
            RecordingOwnerInterests domainInterests = new RecordingOwnerInterests();
            ScenePlayerDomainEventAgent domainEvents = new ScenePlayerDomainEventAgent(
                    messages,
                    actors.actor("scene-domain-events"),
                    new PlayerDomainEventProcessor(),
                    domainInterests
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
            assertEquals(List.of(PlayerDomainVersionedEvent.ownerKey(10001L)), domainInterests.watched);
            assertEquals(List.of(PlayerDomainVersionedEvent.ownerKey(10001L)), domainInterests.unwatched);
            assertEquals(false, domainEvents.stageClears(10001L).isPresent());
            assertEquals(true, service.domainEvents().isPresent());
        }
    }

    @Test
    void profileAwareWrapperCanDriveAllianceAwarenessOnEnterAndLeave() {
        RecordingExecutor executor = new RecordingExecutor();
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
                    ProfileInterestControl.noop()
            );
            ScenePlayerDomainEventAgent domainEvents = new ScenePlayerDomainEventAgent(
                    messages,
                    actors.actor("scene-domain-events")
            );
            SceneAllianceAwarenessAgent allianceEvents = new SceneAllianceAwarenessAgent(
                    messages,
                    actors.actor("scene-alliance-events")
            );
            ProfileAwareSceneService service = new ProfileAwareSceneService(
                    delegate,
                    profiles,
                    domainEvents,
                    allianceEvents
            );

            service.enter(10001L, "room-1", 0, 0);
            executor.runNext();
            executor.runNext();
            executor.runNext();
            allianceEvents.onAllianceChanged(new AllianceMemberChangedEvent(
                    100,
                    10001L,
                    AllianceMemberAction.JOIN,
                    1
            ));
            executor.runNext();
            assertEquals(100, allianceEvents.allianceOf(10001L).orElseThrow().allianceId());
            assertEquals(1, service.stats().projectionReceivedEvents());
            assertEquals(1, service.stats().projectionAppliedEvents());

            service.leave(10001L, "room-1");
            executor.runNext();
            executor.runNext();
            executor.runNext();

            allianceEvents.onAllianceChanged(new AllianceMemberChangedEvent(
                    200,
                    10001L,
                    AllianceMemberAction.JOIN,
                    1
            ));
            executor.runNext();
            assertEquals(false, allianceEvents.allianceOf(10001L).isPresent());
            assertEquals(true, service.allianceEvents().isPresent());
        }
    }

    @Test
    void profileAwareWrapperCanDriveFriendAwarenessOnEnterAndLeave() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingOwnerInterests friendInterests = new RecordingOwnerInterests();
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
                    ProfileInterestControl.noop()
            );
            SceneFriendAwarenessAgent friendEvents = new SceneFriendAwarenessAgent(
                    messages,
                    actors.actor("scene-friend-events"),
                    friendInterests
            );
            ProfileAwareSceneService service = new ProfileAwareSceneService(
                    delegate,
                    profiles,
                    null,
                    null,
                    friendEvents
            );

            service.enter(10001L, "room-1", 0, 0);
            executor.runNext();
            executor.runNext();
            friendEvents.onFriendChanged(new FriendChangedEvent(
                    10001L,
                    20002L,
                    FriendRelationAction.ADD,
                    1
            ));
            executor.runNext();
            assertEquals(true, friendEvents.friendsOf(10001L).orElseThrow().contains(20002L));
            assertEquals(1, service.stats().projectionReceivedEvents());
            assertEquals(1, service.stats().projectionAppliedEvents());

            service.leave(10001L, "room-1");
            executor.runNext();
            executor.runNext();

            assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), friendInterests.watched);
            assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), friendInterests.unwatched);
            assertEquals(false, friendEvents.friendsOf(10001L).isPresent());
            assertEquals(true, service.friendEvents().isPresent());
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            commands.add(command);
        }

        synchronized int queued() {
            return commands.size();
        }

        synchronized void runNext() {
            commands.removeFirst().run();
        }

        void runAll() {
            while (queued() > 0) {
                runNext();
            }
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

    private static final class RecordingOwnerInterests implements OwnerEventInterestControl {
        private final List<String> watched = new ArrayList<>();
        private final List<String> unwatched = new ArrayList<>();

        @Override
        public void watchOwner(String ownerKey) {
            watched.add(ownerKey);
        }

        @Override
        public void unwatchOwner(String ownerKey) {
            unwatched.add(ownerKey);
        }
    }

    private static void awaitQueued(RecordingExecutor executor, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline && executor.queued() < expected) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertEquals(expected, executor.queued());
    }
}
