package com.commonbattle.game.event;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OwnerEventRepairDispatcherTest {
    @Test
    void dueSchedulersAreDrainedByPendingPriorityAndBacklog() {
        AtomicLong nanos = new AtomicLong();
        RecordingInterests lowDelegate = new RecordingInterests();
        RecordingInterests highDelegate = new RecordingInterests();
        OwnerEventRepairScheduler low = new OwnerEventRepairScheduler(
                lowDelegate,
                Duration.ofSeconds(1),
                8,
                1
        );
        OwnerEventRepairScheduler high = new OwnerEventRepairScheduler(
                highDelegate,
                Duration.ofSeconds(1),
                8,
                10
        );
        OwnerEventRepairDispatcher dispatcher = new OwnerEventRepairDispatcher(
                Duration.ofMillis(1),
                1,
                nanos::get
        );
        dispatcher.register(low, Duration.ofNanos(10));
        dispatcher.register(high, Duration.ofNanos(10));

        low.requestRepairOwners(List.of("low:1", "low:2"));
        high.requestRepairOwners(List.of("high:1"));
        nanos.set(10);

        assertEquals(1, dispatcher.drainOnce());
        assertEquals(1, dispatcher.repairDispatcherStats().selectedSchedulers());
        assertEquals(1, dispatcher.repairDispatcherStats().drainedOwners());
        assertEquals(List.of(), lowDelegate.repairs);
        assertEquals(List.of(List.of("high:1")), highDelegate.repairs);

        assertEquals(2, dispatcher.drainOnce());
        assertEquals(List.of(List.of("low:1", "low:2")), lowDelegate.repairs);
        assertEquals(2, dispatcher.repairDispatcherStats().selectedSchedulers());
        assertEquals(3, dispatcher.repairDispatcherStats().drainedOwners());
    }

    @Test
    void dispatcherHonorsPerSchedulerInterval() {
        AtomicLong nanos = new AtomicLong();
        RecordingInterests delegate = new RecordingInterests();
        OwnerEventRepairScheduler repair = new OwnerEventRepairScheduler(
                delegate,
                Duration.ofSeconds(1),
                8,
                5
        );
        OwnerEventRepairDispatcher dispatcher = new OwnerEventRepairDispatcher(
                Duration.ofMillis(1),
                4,
                nanos::get
        );
        dispatcher.register(repair, Duration.ofNanos(10));
        repair.requestRepairOwners(List.of("owner:1"));

        nanos.set(9);
        assertEquals(0, dispatcher.drainOnce());
        assertEquals(1, dispatcher.repairDispatcherStats().emptyRuns());
        assertEquals(List.of(), delegate.repairs);

        nanos.set(10);
        assertEquals(1, dispatcher.drainOnce());
        assertEquals(List.of(List.of("owner:1")), delegate.repairs);
    }

    @Test
    void dispatcherStatsReportLimitAndFailedSchedulerRuns() {
        AtomicLong nanos = new AtomicLong();
        RecordingInterests failing = new RecordingInterests();
        failing.fail = true;
        RecordingInterests waiting = new RecordingInterests();
        OwnerEventRepairScheduler failedRepair = new OwnerEventRepairScheduler(
                failing,
                Duration.ofSeconds(1),
                1,
                20
        );
        OwnerEventRepairScheduler waitingRepair = new OwnerEventRepairScheduler(
                waiting,
                Duration.ofSeconds(1),
                1,
                10
        );
        OwnerEventRepairDispatcher dispatcher = new OwnerEventRepairDispatcher(
                Duration.ofMillis(1),
                1,
                nanos::get
        );
        dispatcher.register(failedRepair, Duration.ofNanos(10));
        dispatcher.register(waitingRepair, Duration.ofNanos(10));
        failedRepair.requestRepairOwners(List.of("failed:1"));
        waitingRepair.requestRepairOwners(List.of("waiting:1"));
        nanos.set(10);

        assertEquals(0, dispatcher.drainOnce());

        OwnerEventRepairDispatcherStats stats = dispatcher.repairDispatcherStats();
        assertEquals(2, stats.registeredSchedulers());
        assertEquals(2, stats.pendingSchedulers());
        assertEquals(1, stats.failedSchedulerRuns());
        assertEquals(1, stats.limitedRuns());
    }

    @Test
    void dispatcherSkipsSchedulerWhileItIsBackingOff() {
        AtomicLong nanos = new AtomicLong();
        RecordingInterests failing = new RecordingInterests();
        failing.fail = true;
        RecordingInterests waiting = new RecordingInterests();
        OwnerEventRepairScheduler failedRepair = new OwnerEventRepairScheduler(
                failing,
                Duration.ofSeconds(1),
                1,
                20,
                new OwnerEventRepairBackoffPolicy(Duration.ofMillis(1), Duration.ofMillis(10), 2.0),
                nanos::get
        );
        OwnerEventRepairScheduler waitingRepair = new OwnerEventRepairScheduler(
                waiting,
                Duration.ofSeconds(1),
                1,
                10
        );
        OwnerEventRepairDispatcher dispatcher = new OwnerEventRepairDispatcher(
                Duration.ofMillis(1),
                1,
                nanos::get
        );
        dispatcher.register(failedRepair, Duration.ofNanos(10));
        dispatcher.register(waitingRepair, Duration.ofNanos(10));
        failedRepair.requestRepairOwners(List.of("failed:1"));
        waitingRepair.requestRepairOwners(List.of("waiting:1"));
        nanos.set(10);

        assertEquals(0, dispatcher.drainOnce());

        nanos.set(20);
        assertEquals(1, dispatcher.drainOnce());

        assertEquals(List.of(List.of("waiting:1")), waiting.repairs);
        assertEquals(0, failedRepair.repairSchedulerStats().backoffSkips());
    }

    private static final class RecordingInterests implements OwnerEventInterestControl {
        private final List<List<String>> repairs = new ArrayList<>();
        private boolean fail;

        @Override
        public void watchOwner(String ownerKey) {
        }

        @Override
        public void unwatchOwner(String ownerKey) {
        }

        @Override
        public void requestRepairOwners(Collection<String> ownerKeys) {
            if (fail) {
                throw new IllegalStateException("repair failed");
            }
            repairs.add(List.copyOf(ownerKeys));
        }
    }
}
