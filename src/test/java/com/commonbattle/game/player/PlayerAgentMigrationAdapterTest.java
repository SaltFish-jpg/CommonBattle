package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.migration.AgentMigrationAcceptRequest;
import com.commonbattle.actor.agent.migration.AgentMigrationSnapshot;
import com.commonbattle.actor.agent.migration.AgentMigrationTask;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStatus;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.config.GameConfigRuntime;
import com.commonbattle.game.config.GameConfigView;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAgentMigrationAdapterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void packerSerializesPlayerSnapshotFromSourceMailbox() {
        Fixture fixture = new Fixture(ServiceId.of(ServiceKind.GAME, "r1", "game-1"));
        PlayerGameAgent agent = fixture.manager.getOrCreate(10001L);
        agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 88)));
        PlayerAgentMigrationStatePacker packer = new PlayerAgentMigrationStatePacker(
                fixture.manager,
                fixture.serializer
        );

        AgentMigrationSnapshot migration = packer.pack(
                AgentIdentity.player(10001L),
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-2"), new ActorRef("player-10001")),
                null
        );
        PlayerStateSnapshot snapshot = fixture.serializer.deserialize(migration.stateBytes());

        assertEquals(PlayerAgentMigrationStatePacker.STATE_TYPE, migration.stateType());
        assertEquals(10001L, snapshot.playerId());
        assertEquals(88, snapshot.bag().itemCounts().get("gold"));
        assertEquals(1, snapshot.revision());
    }

    @Test
    void restoreHandlerMountsMigratedPlayerOnTargetMailbox() {
        Fixture fixture = new Fixture(ServiceId.of(ServiceKind.GAME, "r1", "game-2"));
        AgentIdentity player = AgentIdentity.player(10001L);
        ActorRef actor = new ActorRef("player-10001");
        AgentLocation target = new AgentLocation(fixture.localService, actor);
        fixture.directory.claim(player, target);
        PlayerStateSnapshot snapshot = new PlayerProfile(10001L, SERVER_OPEN_TIME)
                .snapshot(7, 3, CLOCK.instant());
        PlayerProfile restored = PlayerProfile.restore(snapshot);
        restored.bag().restore(new BagSnapshot(java.util.Map.of("gold", 120)));
        PlayerStateSnapshot state = restored.snapshot(8, 3, CLOCK.instant());
        AgentMigrationAcceptRequest request = new AgentMigrationAcceptRequest(
                player,
                actor.id(),
                PlayerAgentMigrationStatePacker.STATE_TYPE,
                fixture.serializer.serialize(state)
        );
        PlayerAgentMigrationRestoreHandler handler = new PlayerAgentMigrationRestoreHandler(
                fixture.manager,
                fixture.serializer
        );

        fixture.lifecycles.acceptMigrated(player, actor.id(), context -> handler.restore(request, context));

        assertTrue(fixture.manager.get(10001L).isPresent());
        assertEquals(120, fixture.manager.getOrCreate(10001L).profile().bag().count("gold"));
        assertEquals(8, fixture.repository.load(10001L).orElseThrow().revision());
    }

    @Test
    void sourceHookRemovesSourceHandleAndRestoresItAfterRollback() {
        Fixture fixture = new Fixture(ServiceId.of(ServiceKind.GAME, "r1", "game-1"));
        PlayerGameAgent agent = fixture.manager.getOrCreate(10001L);
        agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 66)));
        PlayerAgentMigrationStatePacker packer = new PlayerAgentMigrationStatePacker(
                fixture.manager,
                fixture.serializer
        );
        AgentLocation source = new AgentLocation(fixture.localService, new ActorRef("player-10001"));
        AgentLocation target = new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new ActorRef("player-10001"));
        AgentMigrationSnapshot snapshot = packer.pack(AgentIdentity.player(10001L), target, null);
        AgentMigrationTask task = new AgentMigrationTask(
                "task-1",
                AgentIdentity.player(10001L),
                source,
                target,
                snapshot,
                AgentMigrationTaskStatus.MOVED,
                "",
                CLOCK.instant()
        );
        PlayerAgentMigrationSourceHook hook = new PlayerAgentMigrationSourceHook(
                fixture.manager,
                fixture.serializer
        );

        hook.sourceMoved(task);

        assertTrue(fixture.manager.get(10001L).isEmpty());

        hook.rollbackRestored(task);

        assertTrue(fixture.manager.get(10001L).isPresent());
        assertEquals(66, fixture.manager.getOrCreate(10001L).profile().bag().count("gold"));
    }

    private static final class Fixture {
        private final ServiceId localService;
        private final ActorSystem actors = new ActorSystem(Runnable::run, 64);
        private final InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        private final InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();
        private final ProtostuffPlayerStateSnapshotSerializer serializer =
                new ProtostuffPlayerStateSnapshotSerializer();
        private final AgentLifecycleManager lifecycles;
        private final PlayerGameAgentManager manager;

        private Fixture(ServiceId localService) {
            this.localService = localService;
            lifecycles = new AgentLifecycleManager(localService, actors, directory, CLOCK);
            manager = new PlayerGameAgentManager(
                    actors,
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    repository,
                    new FixedGameConfigView(GameConfigRuntime.from(ExampleGameConfigs.basic(1, CLOCK.instant()))),
                    lifecycles,
                    CLOCK,
                    SERVER_OPEN_TIME
            );
        }
    }

    private record FixedGameConfigView(GameConfigRuntime runtime) implements GameConfigView {
        @Override
        public GameConfigRuntime active() {
            return runtime;
        }

        @Override
        public GameConfigRuntime resolve(long playerId) {
            return runtime;
        }

        @Override
        public Optional<GameConfigRuntime> version(long version) {
            return version == runtime.version() ? Optional.of(runtime) : Optional.empty();
        }

        @Override
        public List<Long> versions() {
            return List.of(runtime.version());
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
