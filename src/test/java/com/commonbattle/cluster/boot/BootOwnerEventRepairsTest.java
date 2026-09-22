package com.commonbattle.cluster.boot;

import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.OwnerEventRepairDispatcher;
import com.commonbattle.game.event.OwnerEventRepairSchedulerStats;
import com.commonbattle.game.profile.ProfileChangedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class BootOwnerEventRepairsTest {
    @Test
    void repairControlRegistersSchedulerAndDispatcherHealthComponents() {
        BootRuntime runtime = new BootRuntime();
        OwnerEventRepairDispatcher dispatcher = null;
        try {
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(baseConfig());
            dispatcher = BootOwnerEventRepairs.repairDispatcher(config);
            RecordingOwnerInterests delegate = new RecordingOwnerInterests();

            OwnerEventInterestControl control = BootOwnerEventRepairs.repairControl(
                    runtime,
                    config,
                    dispatcher,
                    "profileEventRepairs",
                    ProfileChangedEvent.TOPIC,
                    delegate
            );

            assertNotSame(delegate, control);
            assertEquals(1, runtime.healthRegistry().ownerEventRepairSchedulers().size());
            assertEquals(1, runtime.healthRegistry().ownerEventRepairIsolationAdmins().size());
            assertEquals(1, dispatcher.repairDispatcherStats().registeredSchedulers());

            control.requestRepairOwner(ProfileChangedEvent.ownerKey(10001L));

            OwnerEventRepairSchedulerStats stats = runtime.healthRegistry()
                    .ownerEventRepairSchedulers()
                    .getFirst()
                    .repairSchedulerStats();
            assertEquals(1, stats.pendingOwners());
            assertEquals(7, stats.defaultPriority());
            assertEquals(7, stats.highestPendingPriority());
            assertEquals(List.of(), delegate.repairs);

            BootOwnerEventRepairs.startRepairDispatcher(runtime, dispatcher);
            dispatcher = null;
            assertEquals(1, runtime.healthRegistry().ownerEventRepairDispatchers().size());
        } finally {
            runtime.close();
            if (dispatcher != null) {
                dispatcher.close();
            }
        }
    }

    @Test
    void disabledTopicFallsBackToDelegateWithoutScheduler() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = baseConfig();
            properties.setProperty("cluster.event.repair.topic.profile.changed.scheduler.enabled", "false");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
            RecordingOwnerInterests delegate = new RecordingOwnerInterests();

            OwnerEventInterestControl control = BootOwnerEventRepairs.repairControl(
                    runtime,
                    config,
                    null,
                    "profileEventRepairs",
                    ProfileChangedEvent.TOPIC,
                    delegate
            );

            assertSame(delegate, control);
            control.requestRepairOwner(ProfileChangedEvent.ownerKey(10001L));

            assertEquals(0, runtime.healthRegistry().ownerEventRepairSchedulers().size());
            assertEquals(List.of(List.of(ProfileChangedEvent.ownerKey(10001L))), delegate.repairs);
        } finally {
            runtime.close();
        }
    }

    private static Properties baseConfig() {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "SCENE");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "scene-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9002");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "1");
        properties.setProperty("cluster.event.repair.dispatcher.interval.millis", "60000");
        properties.setProperty("cluster.event.repair.topic.profile.changed.interval.millis", "60000");
        properties.setProperty("cluster.event.repair.topic.profile.changed.priority", "7");
        properties.setProperty("cluster.event.repair.topic.profile.changed.max.batch.size", "8");
        return properties;
    }

    private static final class RecordingOwnerInterests implements OwnerEventInterestControl {
        private final List<List<String>> repairs = new ArrayList<>();

        @Override
        public void watchOwner(String ownerKey) {
        }

        @Override
        public void unwatchOwner(String ownerKey) {
        }

        @Override
        public void requestRepairOwners(Collection<String> ownerKeys) {
            repairs.add(List.copyOf(ownerKeys));
        }
    }
}
