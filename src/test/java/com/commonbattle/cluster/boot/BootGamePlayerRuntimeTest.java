package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.backpressure.AgentRateLimitPolicy;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.player.BattleStageClearCommand;
import com.commonbattle.game.player.InMemoryPlayerStateRepository;
import com.commonbattle.game.player.PlayerBusinessOperations;
import com.commonbattle.game.player.PlayerProfile;
import com.commonbattle.game.player.UseExpItemsCommand;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.profile.InMemoryProfileSnapshotRepository;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.session.PlayerLoginResult;
import com.commonbattle.observability.RuntimeHealthJsonFormatter;
import com.commonbattle.observability.RuntimeHealthPolicy;
import com.commonbattle.observability.RuntimeHealthProbe;
import com.commonbattle.observability.RuntimeHealthSnapshot;
import com.commonbattle.observability.RuntimeMetricsFormatter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootGamePlayerRuntimeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void configuredPlayerRuntimeCanLoginAndDispatchBusinessCommand() {
        BootRuntime runtime = new BootRuntime();
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = runtime.add("actors", new ActorSystem(executor, 64));
        ServiceDescriptor local = ClusterDescriptors.fromConfig(ClusterNodeConfig.fromProperties(properties()));
        LocalGameConfigCache configCache = runtime.add("configCache",
                new LocalGameConfigCache(new GameConfigValidator(), CLOCK));
        configCache.apply(GameConfigChangedEvent.activePublished(1, ExampleGameConfigs.basic(7, CLOCK.instant())));
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        InMemoryPlayerStateRepository stateRepository = new InMemoryPlayerStateRepository();
        InMemoryProfileSnapshotRepository profileSnapshots = new InMemoryProfileSnapshotRepository();
        PlayerProfile seed = new PlayerProfile(10001L, SERVER_OPEN_TIME);
        seed.bag().restore(new BagSnapshot(Map.of("exp_potion", 2)));
        stateRepository.save(10001L, seed.snapshot(4, 2, CLOCK.instant()));
        List<VersionedEvent> publishedEvents = new ArrayList<>();

        BootGamePlayerRuntime players = BootGamePlayerRuntime.configure(
                runtime,
                local,
                actors,
                new NoopRpcGateway(),
                directory,
                stateRepository,
                configCache,
                profileSnapshots,
                publishedEvents::add,
                publishedEvents::add,
                CLOCK,
                SERVER_OPEN_TIME,
                AgentRateLimitPolicy.perSecond(100, 100)
        );

        PlayerLoginResult login = players.logins().login(10001L, "session-1");
        executor.runAll();
        PlayerCommandResult dispatch = players.dispatcher().dispatch(new PlayerCommand(
                10001L,
                login.session().sessionId(),
                login.session().epoch(),
                1,
                PlayerBusinessOperations.BATTLE_CLEAR_STAGE,
                new BattleStageClearCommand("settle-10001-1", "forest-1")
        ));
        executor.runAll();
        PlayerCommandResult growth = players.dispatcher().dispatch(new PlayerCommand(
                10001L,
                login.session().sessionId(),
                login.session().epoch(),
                2,
                PlayerBusinessOperations.GROWTH_USE_EXP_ITEMS,
                new UseExpItemsCommand(2)
        ));
        executor.runAll();

        assertEquals(PlayerCommandStatus.ACCEPTED, dispatch.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, growth.status());
        assertEquals(4, login.stateRevision());
        assertEquals(2, login.eventRevision());
        assertEquals("player-10001", directory.locate(AgentIdentity.player(10001L)).orElseThrow().actorRef().id());
        assertEquals(30, players.agents().getOrCreate(10001L).profile().bag().count("gold"));
        PlayerDomainVersionedEvent event = assertInstanceOf(PlayerDomainVersionedEvent.class, publishedEvents.getFirst());
        assertEquals(3, event.revision());
        ProfileChangedEvent profileEvent = publishedEvents.stream()
                .filter(ProfileChangedEvent.class::isInstance)
                .map(ProfileChangedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(2, profileEvent.snapshot().level());
        assertTrue(profileEvent.changedFields().contains(ProfileField.LEVEL));
        assertEquals(2, profileSnapshots.find(10001L).orElseThrow().level());
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                players.lifecycles(),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                runtime.healthRegistry(),
                RuntimeHealthPolicy.defaults()
        );
        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);
        assertEquals(1, snapshot.playerAgents().managerCount());
        assertEquals(1, snapshot.playerAgents().loadedAgents());
        assertEquals(0, snapshot.playerAgents().autoSaveSchedulers());
        assertEquals(1, snapshot.playerAgents().drainServices());
        assertTrue(json.contains("\"playerAgents\":{\"managerCount\":1,\"loadedAgents\":1"));
        assertTrue(metrics.contains("commonbattle_player_agents_loaded 1"));
        assertTrue(metrics.contains("commonbattle_player_agent_drain_services 1"));
        assertTrue(players.logins().logoutAndPassivate(login.session(), ignored -> {
        }));
        executor.runAll();
        assertEquals(2, profileSnapshots.find(10001L).orElseThrow().level());
        assertEquals(1, runtime.healthRegistry().commandDispatchers().size());
        assertEquals(1, runtime.healthRegistry().commandAudits().size());
        assertEquals(1, runtime.healthRegistry().lifecycleManagers().size());
        assertNull(players.autoSaves());
        assertTrue(runtime.healthRegistry().drainableComponents().contains(players.logins()));
        assertTrue(runtime.healthRegistry().drainableComponents().contains(players.dispatcher()));
        assertTrue(runtime.healthRegistry().drainableComponents().contains(players.drain()));
    }

    private static Properties properties() {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "GAME");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "game-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9001");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "4");
        return properties;
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runAll() {
            while (!commands.isEmpty()) {
                commands.removeFirst().run();
            }
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
