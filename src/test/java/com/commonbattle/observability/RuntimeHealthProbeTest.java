package com.commonbattle.observability;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcGovernanceConfig;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeHealthProbeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void snapshotContainsActorAgentOutboxAndClusterStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryAgentDirectory agentDirectory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, agentDirectory, CLOCK);
        lifecycles.activate(AgentIdentity.player(10001L), "player-10001");
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(CLOCK);
        outbox.append(profileEvent());
        outbox.markAttemptFailed(1);
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        directory.seed(descriptor(ServiceKind.CENTER, "center-1"));
        directory.seed(descriptor(ServiceKind.GAME, "game-1"));
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                lifecycles,
                outbox,
                directory,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.agents().count(AgentLifecycleState.ACTIVE));
        assertEquals(1, snapshot.outbox().pendingEvents());
        assertEquals(1, snapshot.outbox().failedAttempts());
        assertEquals(1, snapshot.cluster().count(ServiceKind.CENTER));
        assertEquals(1, snapshot.cluster().count(ServiceKind.GAME));
    }

    @Test
    void closedActorSystemReportsDown() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                RuntimeHealthPolicy.defaults()
        );

        actors.close();

        assertEquals(RuntimeHealthStatus.DOWN, probe.snapshot().status());
    }

    @Test
    void snapshotAggregatesRpcGatewayStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1");
        ServiceDescriptor scene = new ServiceDescriptor(
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                new ServiceEndpoint("127.0.0.1", 9002),
                Set.of("scene.hold"),
                Map.of()
        );
        registry.register(game);
        registry.register(scene);
        ClusterDirectory gameDirectory = watchedDirectory(registry);
        ClusterDirectory sceneDirectory = watchedDirectory(registry);
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        RpcGovernanceConfig rpcConfig = new RpcGovernanceConfig(
                Duration.ofSeconds(1),
                10,
                Duration.ofMillis(200),
                100
        );
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        sceneGateway.handle("scene.hold", (request, responder) -> {
        });
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport, true, rpcConfig);
        gameGateway.call(new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                "scene.hold",
                "hello",
                String.class
        ), new NoopCallback());
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(game.id(), actors, new InMemoryAgentDirectory(), CLOCK),
                new InMemoryVersionedEventOutbox(CLOCK),
                gameDirectory,
                java.util.List.of(gameGateway, sceneGateway),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(1, snapshot.rpc().pendingRequests());
        assertEquals(1, snapshot.rpc().sentRequests());
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", 9000),
                Set.of(),
                Map.of()
        );
    }

    private static ClusterDirectory watchedDirectory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private static ProfileChangedEvent profileEvent() {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.NAME),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        10,
                        AppearanceSummary.defaults(),
                        AllianceBrief.none(),
                        new FriendBrief(0, 0),
                        1,
                        CLOCK.instant()
                )
        );
    }

    private static final class InlineExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class NoopCallback implements com.commonbattle.actor.rpc.RpcCallback<String> {
        @Override
        public void success(String response) {
        }

        @Override
        public void failure(Throwable error) {
        }
    }
}
