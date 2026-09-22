package com.commonbattle.actor.agent.migration;

import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedAgentMigrationTargetReceiptStoreTest {
    private final InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
    private final ProtoAgentMigrationTargetReceiptSerializer serializer =
            new ProtoAgentMigrationTargetReceiptSerializer();

    @Test
    void receiptsSurviveStoreRecreation() {
        SerializedAgentMigrationTargetReceiptStore first = storeAt("2026-09-01T00:00:00Z");
        first.save("migration-1", AgentMigrationAcceptResponse.success());

        SerializedAgentMigrationTargetReceiptStore second = storeAt("2026-09-01T00:01:00Z");

        assertEquals(AgentMigrationAcceptResponse.success(), second.find("migration-1").orElseThrow());
        assertEquals(1, second.size());
    }

    @Test
    void purgeDeletesOnlyOldReceipts() {
        storeAt("2026-09-01T00:00:00Z")
                .save("migration-old", AgentMigrationAcceptResponse.success());
        storeAt("2026-09-01T00:02:00Z")
                .save("migration-new", AgentMigrationAcceptResponse.rejected("restore failed"));
        SerializedAgentMigrationTargetReceiptStore store = storeAt("2026-09-01T00:03:00Z");

        int purged = store.purgeBefore(Instant.parse("2026-09-01T00:01:00Z"));

        assertEquals(1, purged);
        assertTrue(store.find("migration-old").isEmpty());
        assertEquals(AgentMigrationAcceptResponse.rejected("restore failed"),
                store.find("migration-new").orElseThrow());
    }

    private SerializedAgentMigrationTargetReceiptStore storeAt(String instant) {
        return new SerializedAgentMigrationTargetReceiptStore(
                bytes,
                serializer,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
        );
    }
}
