package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import org.junit.jupiter.api.Test;

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
}
