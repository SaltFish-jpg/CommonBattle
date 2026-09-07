package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtoAgentMigrationTaskSerializerTest {
    private final ProtoAgentMigrationTaskSerializer serializer = new ProtoAgentMigrationTaskSerializer();

    @Test
    void migrationTaskCanRoundTripWithLeaseFields() {
        AgentMigrationTask task = task();

        AgentMigrationTask decoded = serializer.decode(serializer.encode(task));

        assertTaskEquals(task, decoded);
    }

    @Test
    void migrationTaskDecoderSkipsUnknownFutureFields() throws IOException {
        AgentMigrationTask task = task();
        byte[] encoded = serializer.encode(task);
        byte[] future = new byte[CodedOutputStream.computeStringSize(99, "future-field")];
        CodedOutputStream output = CodedOutputStream.newInstance(future);
        output.writeString(99, "future-field");
        output.flush();
        byte[] combined = Arrays.copyOf(encoded, encoded.length + future.length);
        System.arraycopy(future, 0, combined, encoded.length, future.length);

        AgentMigrationTask decoded = serializer.decode(combined);

        assertTaskEquals(task, decoded);
    }

    private static AgentMigrationTask task() {
        AgentLocation source = new AgentLocation(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                new ActorRef("player-10001")
        );
        AgentLocation target = new AgentLocation(
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new ActorRef("player-10001")
        );
        return new AgentMigrationTask(
                "migration-1",
                AgentIdentity.player(10001L),
                source,
                target,
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1, 2, 3}),
                AgentMigrationTaskStatus.MOVED,
                "retrying",
                Instant.parse("2026-09-01T00:00:00Z"),
                "recovery-1",
                Instant.parse("2026-09-01T00:00:30Z")
        );
    }

    private static void assertTaskEquals(AgentMigrationTask expected, AgentMigrationTask actual) {
        assertEquals(expected.taskId(), actual.taskId());
        assertEquals(expected.identity(), actual.identity());
        assertEquals(expected.source(), actual.source());
        assertEquals(expected.target(), actual.target());
        assertEquals(expected.snapshot().stateType(), actual.snapshot().stateType());
        assertArrayEquals(expected.snapshot().stateBytes(), actual.snapshot().stateBytes());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.reason(), actual.reason());
        assertEquals(expected.updatedAt(), actual.updatedAt());
        assertEquals(expected.leaseOwner(), actual.leaseOwner());
        assertEquals(expected.leaseExpiresAt(), actual.leaseExpiresAt());
    }
}
