package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentDirectoryLease;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.SerializedAgentDirectory;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteAgentDirectoryTest {
    @Test
    void gameNodesShareAgentOwnershipThroughCenterDirectory() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                AgentDirectoryOperations.CLAIM,
                AgentDirectoryOperations.MOVE,
                AgentDirectoryOperations.UNBIND,
                AgentDirectoryOperations.LOCATE
        ));
        ServiceDescriptor gameOne = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor gameTwo = descriptor(ServiceKind.GAME, "game-2", 9002, Set.of("game.resume"));
        ClusterDirectory centerDirectory = new ClusterDirectory(new InMemoryServiceRegistry());
        centerDirectory.seed(center);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
        new CenterAgentDirectoryEndpoint(new InMemoryAgentDirectory()).bind(centerGateway);
        RemoteAgentDirectory directoryOne = remoteDirectory(gameOne, center, topology, transport);
        RemoteAgentDirectory directoryTwo = remoteDirectory(gameTwo, center, topology, transport);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation locationOne = new AgentLocation(gameOne.id(), new ActorRef("player-10001"));
        AgentLocation locationTwo = new AgentLocation(gameTwo.id(), new ActorRef("player-10001"));

        assertTrue(directoryOne.claim(player, locationOne));
        assertFalse(directoryTwo.claim(player, locationTwo));
        assertEquals(locationOne, directoryTwo.locate(player).orElseThrow());

        assertTrue(directoryOne.move(player, locationOne, locationTwo));

        assertEquals(locationTwo, directoryOne.locate(player).orElseThrow());
        directoryOne.unbind(player, locationOne);
        assertEquals(locationTwo, directoryOne.locate(player).orElseThrow());
        directoryTwo.unbind(player, locationTwo);
        assertTrue(directoryOne.locate(player).isEmpty());
    }

    @Test
    void gameNodesAcquireRenewAndTakeOverLeaseThroughCenter() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                AgentDirectoryOperations.ACQUIRE_LEASE,
                AgentDirectoryOperations.RENEW_LEASE,
                AgentDirectoryOperations.TRANSFER_LEASE,
                AgentDirectoryOperations.RELEASE_LEASE,
                AgentDirectoryOperations.LOCATE_LEASE
        ));
        ServiceDescriptor gameOne = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor gameTwo = descriptor(ServiceKind.GAME, "game-2", 9002, Set.of("game.resume"));
        ClusterDirectory centerDirectory = new ClusterDirectory(new InMemoryServiceRegistry());
        centerDirectory.seed(center);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        new CenterAgentDirectoryEndpoint(
                new SerializedAgentDirectory(new InMemoryAtomicBytesStore(), clock)
        ).bind(centerGateway);
        RemoteAgentDirectory directoryOne = remoteDirectory(gameOne, center, topology, transport);
        RemoteAgentDirectory directoryTwo = remoteDirectory(gameTwo, center, topology, transport);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation locationOne = new AgentLocation(gameOne.id(), new ActorRef("player-10001"));
        AgentLocation locationTwo = new AgentLocation(gameTwo.id(), new ActorRef("player-10001"));

        AgentDirectoryLease first = directoryOne.acquire(
                player, locationOne, "game-1-session", Duration.ofSeconds(10)
        ).orElseThrow();
        assertTrue(directoryTwo.acquire(
                player, locationTwo, "game-2-session", Duration.ofSeconds(10)
        ).isEmpty());

        clock.advance(Duration.ofSeconds(10));
        AgentDirectoryLease takenOver = directoryTwo.acquire(
                player, locationTwo, "game-2-session", Duration.ofSeconds(10)
        ).orElseThrow();

        assertEquals(2, takenOver.fencingToken());
        assertTrue(directoryOne.renew(first, Duration.ofSeconds(10)).isEmpty());
        assertFalse(directoryOne.release(first));
        assertEquals(takenOver, directoryOne.locateLease(player).orElseThrow());
        AgentDirectoryLease transferred = directoryTwo.transfer(
                takenOver, locationOne, "handoff:return", Duration.ofSeconds(10)
        ).orElseThrow();
        assertEquals(3, transferred.fencingToken());
        assertTrue(directoryTwo.release(transferred));
    }

    private static RemoteAgentDirectory remoteDirectory(
            ServiceDescriptor local,
            ServiceDescriptor center,
            ClusterTopology topology,
            LocalClusterTransport transport
    ) {
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        directory.seed(center);
        ClusterRpcGateway gateway = new ClusterRpcGateway(local, directory, topology, transport);
        return new RemoteAgentDirectory(gateway);
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
