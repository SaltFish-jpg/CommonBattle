package com.commonbattle.game.event;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnerEventRepairSchedulerTest {
    @Test
    void repairRequestsAreDeduplicatedAndDispatchedByBatch() {
        RecordingInterests delegate = new RecordingInterests();
        OwnerEventRepairScheduler scheduler = new OwnerEventRepairScheduler(
                delegate,
                Duration.ofSeconds(1),
                2
        );

        scheduler.requestRepairOwners(List.of("friend:10001", "friend:10002", "friend:10001"));

        assertEquals(new OwnerEventRepairSchedulerStats(2, 0, 0, 1, 3, 2, 1, 0, 0, 0, 0,
                        0, 0, false, 0, 0, 0, 0, 0, false, false),
                scheduler.repairSchedulerStats());

        assertEquals(2, scheduler.drainOnce());
        assertEquals(List.of(List.of("friend:10001", "friend:10002")), delegate.repairs);
        assertEquals(new OwnerEventRepairSchedulerStats(0, 0, 0, 1, 3, 2, 1, 1, 2, 0, 0,
                        0, 0, false, 0, 0, 0, 0, 0, false, false),
                scheduler.repairSchedulerStats());
    }

    @Test
    void failedDispatchRequeuesOwnersForNextRun() {
        RecordingInterests delegate = new RecordingInterests();
        delegate.fail = true;
        OwnerEventRepairScheduler scheduler = new OwnerEventRepairScheduler(
                delegate,
                Duration.ofSeconds(1),
                2
        );
        scheduler.requestRepairOwners(List.of("alliance:1", "alliance:2"));

        assertThrows(IllegalStateException.class, scheduler::drainOnce);

        assertEquals(new OwnerEventRepairSchedulerStats(2, 0, 0, 1, 2, 2, 0, 1, 0, 1, 0,
                        0, 0, false, 0, 0, 0, 0, 0, false, false),
                scheduler.repairSchedulerStats());

        delegate.fail = false;
        assertEquals(2, scheduler.drainOnce());

        assertEquals(List.of(List.of("alliance:1", "alliance:2")), delegate.repairs);
        assertEquals(new OwnerEventRepairSchedulerStats(0, 0, 0, 1, 2, 2, 0, 2, 2, 1, 0,
                        0, 0, false, 0, 0, 0, 0, 0, false, false),
                scheduler.repairSchedulerStats());
    }

    @Test
    void higherPriorityRepairIsDispatchedFirstAndDuplicatesCanBeUpgraded() {
        RecordingInterests delegate = new RecordingInterests();
        OwnerEventRepairScheduler scheduler = new OwnerEventRepairScheduler(
                delegate,
                Duration.ofSeconds(1),
                2,
                1
        );

        scheduler.requestRepairOwners(List.of("owner:low-1", "owner:upgrade"));
        scheduler.requestRepairOwners(List.of("owner:high"), 10);
        scheduler.requestRepairOwners(List.of("owner:upgrade"), 20);

        assertEquals(new OwnerEventRepairSchedulerStats(3, 1, 20, 3, 4, 3, 1, 0, 0, 0, 0,
                        0, 0, false, 0, 0, 0, 0, 0, false, false),
                scheduler.repairSchedulerStats());

        assertEquals(2, scheduler.drainOnce());
        assertEquals(List.of(List.of("owner:upgrade", "owner:high")), delegate.repairs);
        assertEquals(new OwnerEventRepairSchedulerStats(1, 1, 1, 3, 4, 3, 1, 1, 2, 0, 0,
                        0, 0, false, 0, 0, 0, 0, 0, false, false),
                scheduler.repairSchedulerStats());
    }

    @Test
    void unwatchRemovesPendingRepair() {
        RecordingInterests delegate = new RecordingInterests();
        OwnerEventRepairScheduler scheduler = new OwnerEventRepairScheduler(
                delegate,
                Duration.ofSeconds(1),
                4
        );

        scheduler.requestRepairOwners(List.of("friend:10001", "friend:10002"));
        scheduler.unwatchOwner("friend:10001");
        scheduler.drainOnce();

        assertEquals(List.of("friend:10001"), delegate.unwatched);
        assertEquals(List.of(List.of("friend:10002")), delegate.repairs);
    }

    @Test
    void failedDispatchBacksOffBeforeRetrying() {
        AtomicLong nanos = new AtomicLong();
        RecordingInterests delegate = new RecordingInterests();
        delegate.fail = true;
        OwnerEventRepairScheduler scheduler = new OwnerEventRepairScheduler(
                delegate,
                Duration.ofSeconds(1),
                2,
                0,
                new OwnerEventRepairBackoffPolicy(Duration.ofMillis(100), Duration.ofSeconds(1), 2.0),
                nanos::get
        );
        scheduler.requestRepairOwners(List.of("friend:10001"));

        assertThrows(IllegalStateException.class, scheduler::drainOnce);

        OwnerEventRepairSchedulerStats backedOff = scheduler.repairSchedulerStats();
        assertEquals(1, backedOff.pendingOwners());
        assertEquals(1, backedOff.consecutiveFailures());
        assertEquals(1, backedOff.failedRuns());
        assertEquals(100, backedOff.backoffRemainingMillis());
        assertEquals(0, scheduler.drainOnce());
        assertEquals(1, scheduler.repairSchedulerStats().backoffSkips());
        assertEquals(List.of(), delegate.repairs);

        delegate.fail = false;
        nanos.set(Duration.ofMillis(100).toNanos());
        assertEquals(1, scheduler.drainOnce());

        OwnerEventRepairSchedulerStats recovered = scheduler.repairSchedulerStats();
        assertEquals(0, recovered.pendingOwners());
        assertEquals(0, recovered.consecutiveFailures());
        assertEquals(0, recovered.backoffRemainingMillis());
        assertEquals(List.of(List.of("friend:10001")), delegate.repairs);
    }

    @Test
    void singleOwnerFailuresAreIsolatedWithoutBlockingHealthyOwners() {
        AtomicLong nanos = new AtomicLong();
        RecordingInterests delegate = new RecordingInterests();
        delegate.failingOwners.add("owner:bad");
        OwnerEventRepairScheduler scheduler = new OwnerEventRepairScheduler(
                delegate,
                Duration.ofSeconds(1),
                2,
                0,
                OwnerEventRepairBackoffPolicy.disabled(),
                new OwnerEventRepairIsolationPolicy(1, Duration.ofNanos(100)),
                nanos::get
        );
        scheduler.requestRepairOwners(List.of("owner:bad", "owner:good"));

        assertThrows(IllegalStateException.class, scheduler::drainOnce);
        assertEquals(2, scheduler.repairSchedulerStats().pendingOwners());
        assertEquals(0, scheduler.repairSchedulerStats().isolatedOwners());
        assertEquals(true, scheduler.repairSchedulerStats().singleOwnerProbeMode());

        assertThrows(IllegalStateException.class, scheduler::drainOnce);
        OwnerEventRepairSchedulerStats isolated = scheduler.repairSchedulerStats();
        assertEquals(1, isolated.pendingOwners());
        assertEquals(1, isolated.isolatedOwners());
        assertEquals(1, isolated.isolatedOwnersTotal());

        assertEquals(1, scheduler.drainOnce());
        assertEquals(List.of(List.of("owner:good")), delegate.repairs);

        delegate.failingOwners.clear();
        nanos.set(100);
        assertEquals(1, scheduler.repairSchedulerStats().pendingOwners());
        assertEquals(0, scheduler.repairSchedulerStats().isolatedOwners());
        assertEquals(1, scheduler.repairSchedulerStats().releasedIsolatedOwners());
        assertEquals(1, scheduler.drainOnce());

        assertEquals(List.of(List.of("owner:good"), List.of("owner:bad")), delegate.repairs);
    }

    @Test
    void isolatedOwnerCanBeReleasedByAdmin() {
        AtomicLong nanos = new AtomicLong();
        RecordingInterests delegate = new RecordingInterests();
        delegate.failingOwners.add("owner:bad");
        OwnerEventRepairScheduler scheduler = new OwnerEventRepairScheduler(
                delegate,
                Duration.ofSeconds(1),
                1,
                0,
                OwnerEventRepairBackoffPolicy.disabled(),
                new OwnerEventRepairIsolationPolicy(1, Duration.ofSeconds(10)),
                nanos::get
        );
        scheduler.requestRepairOwners(List.of("owner:bad"));

        assertThrows(IllegalStateException.class, scheduler::drainOnce);

        assertEquals(1, scheduler.isolatedOwners().size());
        assertEquals("owner:bad", scheduler.isolatedOwners().getFirst().ownerKey());
        assertEquals(1, scheduler.releaseIsolatedOwner("owner:bad"));
        assertEquals(0, scheduler.isolatedOwners().size());
        assertEquals(1, scheduler.repairSchedulerStats().pendingOwners());
        assertEquals(0, scheduler.releaseIsolatedOwner("owner:bad"));
    }

    private static final class RecordingInterests implements OwnerEventInterestControl {
        private final List<String> unwatched = new ArrayList<>();
        private final List<List<String>> repairs = new ArrayList<>();
        private final Set<String> failingOwners = new HashSet<>();
        private boolean fail;

        @Override
        public void watchOwner(String ownerKey) {
        }

        @Override
        public void unwatchOwner(String ownerKey) {
            unwatched.add(ownerKey);
        }

        @Override
        public void requestRepairOwners(Collection<String> ownerKeys) {
            if (fail || ownerKeys.stream().anyMatch(failingOwners::contains)) {
                throw new IllegalStateException("repair failed");
            }
            repairs.add(List.copyOf(ownerKeys));
        }
    }
}
