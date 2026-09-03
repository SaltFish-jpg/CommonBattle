package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.rpc.ActorRpcClient;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.registry.RegistryLeaseRenewer;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeHealthRegistryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void registerCollectsKnownRuntimeComponentsWithoutDuplicates() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        GameConfigAutoRecovery recovery = new GameConfigAutoRecovery(callback -> {
        });
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        ActorRpcClient actorRpc = new ActorRpcClient(actors, actors.actor("player-1"), new NoopRpcGateway());

        registry.register(List.of(cache, recovery, actorRpc));
        registry.register(cache);

        assertEquals(1, registry.configCaches().size());
        assertEquals(1, registry.configRecoveries().size());
        assertEquals(1, registry.actorRpcClients().size());
    }

    @Test
    void leaseRenewersIncludeStartedClusterNodes() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        InMemoryServiceRegistry serviceRegistry = new InMemoryServiceRegistry(CLOCK);
        ClusterNode node = new ClusterNode(serviceRegistry, descriptor(), new ClusterDirectory(serviceRegistry));

        registry.register(node);
        assertEquals(0, registry.leaseRenewers().size());

        try {
            node.start(Set.of(), Duration.ofSeconds(5), Duration.ofSeconds(1));

            assertEquals(1, registry.leaseRenewers().size());
            assertEquals(1, registry.drainableComponents().size());
        } finally {
            node.close();
        }
    }

    private static ServiceDescriptor descriptor() {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                new ServiceEndpoint("127.0.0.1", 9001),
                Set.of(),
                Map.of()
        );
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
