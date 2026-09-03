package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentDirectoryPayloadCodecsTest {
    @Test
    void agentDirectoryPayloadsCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = AgentDirectoryPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation from = new AgentLocation(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                new ActorRef("player-10001")
        );
        AgentLocation to = new AgentLocation(
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new ActorRef("player-10001")
        );
        ClusterEnvelope request = new ClusterEnvelope(
                1,
                from.serviceId(),
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                AgentDirectoryOperations.MOVE,
                new AgentDirectoryMoveRequest(player, from, to)
        );
        ClusterEnvelope response = new ClusterEnvelope(
                1,
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                from.serviceId(),
                "$rpc.success",
                new AgentDirectoryLocateResponse(to)
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        AgentDirectoryMoveRequest move = (AgentDirectoryMoveRequest) decodedRequest.payload();
        AgentDirectoryLocateResponse locate = (AgentDirectoryLocateResponse) decodedResponse.payload();
        assertEquals(player, move.identity());
        assertEquals(from, move.expectedCurrent());
        assertEquals(to, move.next());
        assertEquals(to, locate.location());
    }
}
