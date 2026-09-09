package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.registry.RemoteRegistryRecoveryScheduler;
import com.commonbattle.cluster.registry.RemoteServiceRegistry;

/**
 * 远程注册目录恢复启动装配。
 */
final class BootRegistryRecovery {
    private BootRegistryRecovery() {
    }

    static RemoteRegistryRecoveryScheduler configure(
            BootRuntime runtime,
            ClusterNodeConfig config,
            RemoteServiceRegistry registry
    ) {
        if (!config.registryRecoveryEnabled()) {
            return null;
        }
        RemoteRegistryRecoveryScheduler scheduler = runtime.add(
                "remoteRegistryRecovery",
                new RemoteRegistryRecoveryScheduler(registry, config.registryRecoveryInterval())
        );
        scheduler.start();
        return scheduler;
    }
}
