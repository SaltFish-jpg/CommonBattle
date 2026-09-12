package com.commonbattle.game.session;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigPublishStatus;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.player.InMemoryPlayerStateRepository;
import com.commonbattle.game.player.PlayerGameAgentManager;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerClientConnectionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void connectLogsInBindsWriterAndFlushesOfflineMessages() {
        Fixture fixture = Fixture.create();
        List<PlayerOutboundMessage> written = new ArrayList<>();
        fixture.outbound.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "offline-one"));

        PlayerClientConnectionResult result = fixture.connections.connect(10001L, "session-1", message -> {
            written.add(message);
            return true;
        });

        assertEquals(1, result.login().session().epoch());
        assertEquals(new PlayerOutboundDeliveryResult(1, 0, 0, 0), result.offlineFlush());
        assertEquals(List.of("offline-one"), written.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(0, fixture.outbound.pendingOffline(10001L).size());
        assertEquals(1, fixture.outbound.deliveryStats().activeConnections());
        assertEquals(1, fixture.agents.loadedAgents());
    }

    @Test
    void reconnectReplacesOldWriterAndUsesNewEpoch() {
        Fixture fixture = Fixture.create();
        List<PlayerOutboundMessage> firstWriter = new ArrayList<>();
        List<PlayerOutboundMessage> secondWriter = new ArrayList<>();

        PlayerClientConnectionResult first = fixture.connections.connect(10001L, "session-1", message -> {
            firstWriter.add(message);
            return true;
        });
        PlayerClientConnectionResult second = fixture.connections.connect(10001L, "session-2", message -> {
            secondWriter.add(message);
            return true;
        });
        fixture.outbound.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "after-reconnect"));

        assertEquals(1, first.login().session().epoch());
        assertEquals(2, second.login().session().epoch());
        assertEquals(List.of(), firstWriter.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(List.of("after-reconnect"), secondWriter.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(1, fixture.outbound.deliveryStats().activeConnections());
    }

    @Test
    void reconnectFlushesUnackedThenOfflineMessages() {
        Fixture fixture = Fixture.create();
        List<PlayerOutboundMessage> firstWriter = new ArrayList<>();
        List<PlayerOutboundMessage> secondWriter = new ArrayList<>();
        PlayerClientConnectionResult first = fixture.connections.connect(10001L, "session-1", message -> {
            firstWriter.add(message);
            return true;
        });
        fixture.outbound.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "unacked-online"));
        fixture.connections.disconnect(first.login().session());
        fixture.outbound.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "offline-after-drop"));

        PlayerClientConnectionResult second = fixture.connections.connect(10001L, "session-2", message -> {
            secondWriter.add(message);
            return true;
        });

        assertEquals(2, second.login().session().epoch());
        assertEquals(new PlayerOutboundDeliveryResult(2, 0, 0, 0), second.offlineFlush());
        assertEquals(List.of("unacked-online"), firstWriter.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(List.of("unacked-online", "offline-after-drop"),
                secondWriter.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(2, fixture.outbound.pendingAck(10001L).size());
        assertEquals(0, fixture.outbound.pendingOffline(10001L).size());
    }

    @Test
    void disconnectUnbindsSessionAndFutureMessagesBecomeOffline() {
        Fixture fixture = Fixture.create();
        PlayerClientConnectionResult connected = fixture.connections.connect(10001L, "session-1", ignored -> true);

        fixture.connections.disconnect(connected.login().session());
        PlayerOutboundDeliveryResult result = fixture.outbound.deliver(new PlayerOutboundEnvelope(
                Set.of(10001L),
                "system.notice",
                "offline-after-disconnect"
        ));

        assertEquals(new PlayerOutboundDeliveryResult(0, 1, 0, 0), result);
        assertEquals(0, fixture.outbound.deliveryStats().activeConnections());
        assertEquals(List.of("offline-after-disconnect"), fixture.outbound.pendingOffline(10001L).stream()
                .map(PlayerOutboundMessage::payload)
                .toList());
    }

    @Test
    void disconnectAndPassivateSavesThroughPlayerMailbox() {
        Fixture fixture = Fixture.create();
        PlayerClientConnectionResult connected = fixture.connections.connect(10001L, "session-1", ignored -> true);
        List<Long> savedPlayers = new ArrayList<>();

        assertTrue(fixture.connections.disconnectAndPassivate(
                connected.login().session(),
                snapshot -> savedPlayers.add(snapshot.playerId())
        ));
        fixture.executor.runAll();

        assertEquals(List.of(10001L), savedPlayers);
        assertEquals(0, fixture.agents.loadedAgents());
        assertEquals(0, fixture.outbound.deliveryStats().activeConnections());
    }

    private record Fixture(
            RecordingExecutor executor,
            PlayerGameAgentManager agents,
            PlayerOutboundDeliveryHub outbound,
            PlayerClientConnectionService connections
    ) {
        private static Fixture create() {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                    actors,
                    directory,
                    CLOCK
            );
            InMemoryGameConfigRegistry configs = new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
            assertEquals(GameConfigPublishStatus.PUBLISHED,
                    configs.publish(ExampleGameConfigs.basic(7, CLOCK.instant())).status());
            PlayerGameAgentManager agents = new PlayerGameAgentManager(
                    actors,
                    messages,
                    new InMemoryPlayerStateRepository(),
                    configs,
                    lifecycles,
                    CLOCK,
                    SERVER_OPEN_TIME
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            PlayerLoginService logins = new PlayerLoginService(agents, sessions);
            PlayerOutboundDeliveryHub outbound = new PlayerOutboundDeliveryHub(
                    sessions,
                    CLOCK,
                    8,
                    PlayerDeliveryOverflowStrategy.DROP_OLDEST
            );
            return new Fixture(executor, agents, outbound, new PlayerClientConnectionService(logins, outbound));
        }
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
