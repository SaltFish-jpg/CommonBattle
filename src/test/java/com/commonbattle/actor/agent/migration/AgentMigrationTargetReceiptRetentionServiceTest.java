package com.commonbattle.actor.agent.migration;

import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMigrationTargetReceiptRetentionServiceTest {
    private final InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
    private final ProtoAgentMigrationTargetReceiptSerializer serializer =
            new ProtoAgentMigrationTargetReceiptSerializer();

    @Test
    void purgeUsesRetentionCutoffAndRecordsStats() {
        storeAt("2026-09-01T00:00:00Z")
                .save("migration-old", AgentMigrationAcceptResponse.success());
        storeAt("2026-09-01T00:10:00Z")
                .save("migration-new", AgentMigrationAcceptResponse.success());
        SerializedAgentMigrationTargetReceiptStore store = storeAt("2026-09-01T00:15:00Z");
        AgentMigrationTargetReceiptRetentionService retention =
                new AgentMigrationTargetReceiptRetentionService(
                        store,
                        Clock.fixed(Instant.parse("2026-09-01T00:15:00Z"), ZoneOffset.UTC),
                        Duration.ofMinutes(10)
                );

        int purged = retention.purge();

        assertEquals(1, purged);
        assertTrue(store.find("migration-old").isEmpty());
        assertTrue(store.find("migration-new").isPresent());
        assertEquals(new AgentMigrationTargetReceiptRetentionStats(1, 1, 0), retention.stats());
    }

    private SerializedAgentMigrationTargetReceiptStore storeAt(String instant) {
        return new SerializedAgentMigrationTargetReceiptStore(
                bytes,
                serializer,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
        );
    }
}
