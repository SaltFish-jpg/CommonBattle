package com.commonbattle.cluster.boot;

import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.OwnerEventRepairDispatcher;
import com.commonbattle.game.event.OwnerEventRepairScheduler;

/**
 * owner 事件投影修复启动装配。
 * Scene、Chat 等进程共享同一套调度策略，避免不同 topic 的退避、隔离和健康注册口径分叉。
 */
final class BootOwnerEventRepairs {
    private BootOwnerEventRepairs() {
    }

    static OwnerEventInterestControl repairControl(
            BootRuntime runtime,
            ClusterNodeConfig config,
            OwnerEventRepairDispatcher dispatcher,
            String name,
            String topic,
            OwnerEventInterestControl delegate
    ) {
        if (!config.eventRepairSchedulerEnabled(topic)) {
            return delegate;
        }
        OwnerEventRepairScheduler scheduler = runtime.add(name, new OwnerEventRepairScheduler(
                delegate,
                config.eventRepairInterval(topic),
                config.eventRepairMaxBatchSize(topic),
                config.eventRepairPriority(topic),
                config.eventRepairBackoffPolicy(topic),
                config.eventRepairIsolationPolicy(topic)
        ));
        if (dispatcher == null) {
            scheduler.start();
        } else {
            dispatcher.register(scheduler, config.eventRepairInterval(topic));
        }
        return scheduler;
    }

    static OwnerEventRepairDispatcher repairDispatcher(ClusterNodeConfig config) {
        if (!config.eventRepairDispatcherEnabled()) {
            return null;
        }
        return new OwnerEventRepairDispatcher(
                config.eventRepairDispatcherInterval(),
                config.eventRepairDispatcherMaxDrainsPerTick()
        );
    }

    static void startRepairDispatcher(BootRuntime runtime, OwnerEventRepairDispatcher dispatcher) {
        if (dispatcher == null) {
            return;
        }
        runtime.add("eventRepairDispatcher", dispatcher).start();
    }
}
