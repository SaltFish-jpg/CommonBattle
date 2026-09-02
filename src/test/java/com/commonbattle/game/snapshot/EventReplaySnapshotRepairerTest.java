package com.commonbattle.game.snapshot;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventReplaySnapshotRepairerTest {
    @Test
    void delegatesRepairByTopicAndStoresLastReport() {
        EventReplaySnapshotRepairer repairer = new EventReplaySnapshotRepairer()
                .register("profile.changed", ownerKeys -> new SnapshotRepairReport(ownerKeys.stream()
                        .map(ownerKey -> SnapshotRepairResult.refreshed(ownerKey, 1))
                        .toList()));

        repairer.repair("profile.changed", Set.of("profile:10001", "profile:10002"));

        assertEquals(2, repairer.lastReport().refreshed());
    }

    @Test
    void throwsWhenTopicHasNoRepairer() {
        EventReplaySnapshotRepairer repairer = new EventReplaySnapshotRepairer();

        assertThrows(IllegalStateException.class, () -> repairer.repair("profile.changed", Set.of("profile:10001")));
    }

    @Test
    void throwsWhenRepairReportContainsInvalidOwner() {
        EventReplaySnapshotRepairer repairer = new EventReplaySnapshotRepairer()
                .register("profile.changed", ownerKeys -> new SnapshotRepairReport(java.util.List.of(
                        SnapshotRepairResult.invalidOwnerKey("bad")
                )));

        assertThrows(IllegalStateException.class, () -> repairer.repair("profile.changed", Set.of("bad")));
    }
}
