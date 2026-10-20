package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentDirectoryLease;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

    @Test
    void leasePayloadsPreserveFencingFieldsThroughClusterEnvelope() {
        PayloadCodecRegistry registry = AgentDirectoryPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation location = new AgentLocation(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                new ActorRef("player-10001")
        );
        AgentDirectoryLease lease = new AgentDirectoryLease(
                player,
                location,
                "game-1-session",
                7,
                Instant.parse("2026-01-01T00:00:10Z")
        );
        ClusterEnvelope request = new ClusterEnvelope(
                1,
                location.serviceId(),
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                AgentDirectoryOperations.RENEW_LEASE,
                new AgentDirectoryLeaseRenewRequest(lease, 10_000)
        );
        ClusterEnvelope response = new ClusterEnvelope(
                1,
                request.target(),
                request.source(),
                "$rpc.success",
                new AgentDirectoryLeaseResponse(lease)
        );

        AgentDirectoryLeaseRenewRequest decodedRequest = (AgentDirectoryLeaseRenewRequest) codec
                .decode(codec.encode(request)).payload();
        AgentDirectoryLeaseResponse decodedResponse = (AgentDirectoryLeaseResponse) codec
                .decode(codec.encode(response)).payload();

        assertEquals(lease, decodedRequest.lease());
        assertEquals(10_000, decodedRequest.leaseTtlMillis());
        assertEquals(lease, decodedResponse.lease());

        AgentDirectoryLeaseTransferRequest transfer = new AgentDirectoryLeaseTransferRequest(
                lease,
                new AgentLocation(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                        new ActorRef("player-10001")
                ),
                "handoff:migration-1",
                10_000
        );
        ClusterEnvelope transferEnvelope = new ClusterEnvelope(
                1,
                request.source(),
                request.target(),
                AgentDirectoryOperations.TRANSFER_LEASE,
                transfer
        );
        AgentDirectoryLeaseTransferRequest decodedTransfer = (AgentDirectoryLeaseTransferRequest) codec
                .decode(codec.encode(transferEnvelope)).payload();
        assertEquals(transfer, decodedTransfer);
    }
}
