package com.commonbattle.game.player;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLeaseSupervisor;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.SerializedAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.config.GameConfigPublishStatus;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import com.commonbattle.observability.RuntimeHealthJsonFormatter;
import com.commonbattle.observability.RuntimeHealthPolicy;
import com.commonbattle.observability.RuntimeHealthProbe;
import com.commonbattle.observability.RuntimeHealthRegistry;
import com.commonbattle.observability.RuntimeHealthStatus;
import com.commonbattle.observability.RuntimeMetricsFormatter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAgentOwnershipFencingEndToEndTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T02:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void newerOwnerFencesSplitBrainAgentBeforeStateOrPushCanEscape() {
        SerializedPlayerMutationRepository mutations = new SerializedPlayerMutationRepository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository(),
                new ProtostuffPlayerStateSnapshotSerializer()
        );
        RuntimeNode first = node("game-1", mutations);
        RuntimeNode second = node("game-2", mutations);
        PlayerGameAgent stale = first.manager().getOrCreate(10001L);
        first.executor().runAll();
        PlayerGameAgent owner = second.manager().getOrCreate(10001L);
        second.executor().runAll();
        AtomicReference<Object> staleCallback = new AtomicReference<>();

        stale.onLogin("daily-login", staleCallback::set);
        first.executor().runAll();

        assertTrue(stale.isFenced());
        assertNull(staleCallback.get());
        assertEquals(0, first.manager().loadedAgents());
        assertEquals(1, first.manager().fencingConflicts());
        assertEquals(AgentLifecycleState.FENCED,
                first.lifecycles().record(AgentIdentity.player(10001L)).orElseThrow().state());
        assertTrue(first.directory().locate(AgentIdentity.player(10001L)).isEmpty());
        assertEquals(0, mutations.load(10001L).orElseThrow().revision());
        assertEquals(0, mutations.load(10001L).orElseThrow().bag().itemCounts().getOrDefault("gold", 0));
        RuntimeHealthRegistry healthRegistry = new RuntimeHealthRegistry();
        healthRegistry.register(first.manager());
        var health = new RuntimeHealthProbe(
                CLOCK,
                first.actors(),
                first.lifecycles(),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                healthRegistry,
                RuntimeHealthPolicy.defaults()
        ).snapshot();
        assertEquals(RuntimeHealthStatus.DEGRADED, health.status());
        assertTrue(health.issues().stream().anyMatch(issue ->
                "ownership_fencing_conflicts".equals(issue.code())));
        assertTrue(RuntimeHealthJsonFormatter.format(health).contains("\"fencingConflicts\":1"));
        assertTrue(RuntimeMetricsFormatter.format(health)
                .contains("commonbattle_player_agent_fencing_conflicts_total 1"));

        owner.onLogin("daily-login", ignored -> {
        });
        second.executor().runAll();

        assertEquals(1, second.manager().loadedAgents());
        assertEquals(1, mutations.load(10001L).orElseThrow().revision());
        assertEquals(100, mutations.load(10001L).orElseThrow().bag().itemCounts().get("gold"));
    }

    @Test
    void staleMigrationPayloadCannotRegressPersistentCommandAndResponseCheckpoint() {
        SerializedPlayerMutationRepository mutations = new SerializedPlayerMutationRepository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository(),
                new ProtostuffPlayerStateSnapshotSerializer()
        );
        PlayerCommand firstCommand = command(1);
        PlayerCommand secondCommand = command(2);
        PlayerCommandSequencer firstWatermark = new PlayerCommandSequencer();
        firstWatermark.commit(firstCommand, PlayerCommandStatus.ACCEPTED);
        PlayerCommandSequencer latestWatermark = new PlayerCommandSequencer();
        latestWatermark.commit(firstCommand, PlayerCommandStatus.ACCEPTED);
        latestWatermark.commit(secondCommand, PlayerCommandStatus.ACCEPTED);
        PlayerStateSnapshot stalePayload = new PlayerProfile(10001L, SERVER_OPEN_TIME)
                .snapshot(1, CLOCK.instant())
                .withCommandSequencer(firstWatermark.snapshotForPlayer(10001L));
        PlayerStateSnapshot persisted = new PlayerProfile(10001L, SERVER_OPEN_TIME)
                .snapshot(2, CLOCK.instant())
                .withCommandSequencer(latestWatermark.snapshotForPlayer(10001L));
        PlayerBusinessResponse persistedResponse = PlayerBusinessResponse.success(
                secondCommand, PlayerBusinessAck.OK
        );
        mutations.commit(
                persisted,
                List.of(),
                new PlayerBusinessResponseSnapshot(10001L, List.of(persistedResponse))
        );
        RuntimeNode target = node("game-2", mutations);

        PlayerGameAgentHandle restored = target.manager().restoreMigrated(
                stalePayload, target.actors().actor("player-10001")
        );
        PlayerBusinessResponseSnapshot responses = target.manager().migrationBusinessResponses(
                10001L,
                PlayerBusinessResponseSnapshot.empty(10001L)
        );

        assertEquals(2, restored.snapshot().revision());
        assertEquals(2, restored.snapshot().commandSequencer().watermarks().getFirst().lastSequence());
        assertEquals(List.of(persistedResponse), responses.responses());
    }

    @Test
    void rollbackReclaimsTokenForSourceAgentThatStayedInMemory() {
        SerializedPlayerMutationRepository mutations = new SerializedPlayerMutationRepository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository(),
                new ProtostuffPlayerStateSnapshotSerializer()
        );
        RuntimeNode source = node("game-1", mutations);
        PlayerGameAgent agent = source.manager().getOrCreate(10001L);
        source.executor().runAll();
        agent.onLogin("daily-login", ignored -> {
        });
        source.executor().runAll();
        AtomicReference<PlayerStateSnapshot> migrationSnapshot = new AtomicReference<>();
        agent.exportForMigration(migrationSnapshot::set);
        source.executor().runAll();

        mutations.acquireOwner(migrationSnapshot.get(), "failed-target-owner");
        source.manager().reclaimAfterMigrationRollback(migrationSnapshot.get());
        agent.clearBattleStage("settle-after-rollback", "forest-1", ignored -> {
        });
        source.executor().runAll();

        assertTrue(!agent.isFenced());
        assertEquals(1, source.manager().loadedAgents());
        assertEquals(3, mutations.load(10001L).orElseThrow().revision());
        assertEquals(130, mutations.load(10001L).orElseThrow().bag().itemCounts().get("gold"));
    }

    @Test
    void activatedPlayerPersistsTheSameFencingTokenAsAgentDirectory() {
        SerializedPlayerMutationRepository mutations = new SerializedPlayerMutationRepository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository(),
                new ProtostuffPlayerStateSnapshotSerializer()
        );
        SerializedAgentDirectory directory = new SerializedAgentDirectory(
                new InMemoryAtomicBytesStore(), CLOCK
        );
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        AtomicReference<AgentLifecycleManager> lifecycleRef = new AtomicReference<>();
        try (AgentLeaseSupervisor leases = new AgentLeaseSupervisor(
                directory,
                "game-1-session",
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                CLOCK,
                lease -> lifecycleRef.get().handleLeaseLost(lease)
        )) {
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                    actors,
                    directory,
                    CLOCK,
                    leases
            );
            lifecycleRef.set(lifecycles);
            InMemoryGameConfigRegistry configs = new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
            configs.publish(ExampleGameConfigs.basic(1, CLOCK.instant()));
            PlayerGameAgentManager manager = new PlayerGameAgentManager(
                    actors,
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    mutations,
                    configs,
                    lifecycles,
                    CLOCK,
                    SERVER_OPEN_TIME
            );

            manager.getOrCreate(10001L);
            executor.runAll();

            long directoryToken = directory.locateLease(AgentIdentity.player(10001L))
                    .orElseThrow().fencingToken();
            long mutationToken = PlayerMutationOwnerToken.decode(
                    mutations.loadRecord(10001L).orElseThrow().ownerToken()
            ).orElseThrow().fencingToken();
            assertEquals(directoryToken, mutationToken);
        }
    }

    private static PlayerCommand command(long sequence) {
        return new PlayerCommand(
                10001L,
                "session-1",
                1,
                sequence,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
        );
    }

    private static RuntimeNode node(String instance, PlayerStateRepository repository) {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                ServiceId.of(ServiceKind.GAME, "r1", instance), actors, directory, CLOCK
        );
        InMemoryGameConfigRegistry configs = new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
        assertEquals(GameConfigPublishStatus.PUBLISHED,
                configs.publish(ExampleGameConfigs.basic(1, CLOCK.instant())).status());
        PlayerGameAgentManager manager = new PlayerGameAgentManager(
                actors, messages, repository, configs, lifecycles, CLOCK, SERVER_OPEN_TIME
        );
        return new RuntimeNode(executor, actors, directory, lifecycles, manager);
    }

    private record RuntimeNode(
            RecordingExecutor executor,
            ActorSystem actors,
            InMemoryAgentDirectory directory,
            AgentLifecycleManager lifecycles,
            PlayerGameAgentManager manager
    ) {
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
        }

        synchronized void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
