package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentMigrationPayloadCodecsTest {
    @Test
    void migrationPayloadsCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = AgentMigrationPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        ServiceId source = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId target = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        byte[] state = "profile-v1".getBytes(StandardCharsets.UTF_8);
        ClusterEnvelope request = new ClusterEnvelope(
                1,
                source,
                target,
                AgentMigrationOperations.ACCEPT,
                new AgentMigrationAcceptRequest(
                        "migration-10001",
                        AgentIdentity.player(10001L),
                        "player-10001",
                        "player.snapshot.v1",
                        state
                )
        );
        ClusterEnvelope response = new ClusterEnvelope(
                1,
                target,
                source,
                "$rpc.success",
                AgentMigrationAcceptResponse.success()
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        AgentMigrationAcceptRequest migration = (AgentMigrationAcceptRequest) decodedRequest.payload();
        AgentMigrationAcceptResponse accepted = (AgentMigrationAcceptResponse) decodedResponse.payload();
        assertEquals("migration-10001", migration.taskId());
        assertEquals(AgentIdentity.player(10001L), migration.identity());
        assertEquals("player-10001", migration.actorId());
        assertEquals("player.snapshot.v1", migration.stateType());
        assertArrayEquals(state, migration.stateBytes());
        assertEquals(AgentMigrationAcceptResponse.success(), accepted);
    }
}
