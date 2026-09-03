package com.commonbattle.cluster.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 本地集群进程管理器。
 * 它只负责按启动计划拉起和停止进程，不绑定具体配置系统或容器平台。
 */
public final class LocalClusterProcessManager {
    private final ClusterProcessLauncher launcher;
    private final ClusterReadinessProbe readinessProbe;
    private final ClusterStartupPolicy startupPolicy;

    public LocalClusterProcessManager(ClusterProcessLauncher launcher) {
        this(launcher, ClusterReadinessProbe.alwaysReady(), ClusterStartupPolicy.noWait());
    }

    public LocalClusterProcessManager(
            ClusterProcessLauncher launcher,
            ClusterReadinessProbe readinessProbe,
            ClusterStartupPolicy startupPolicy
    ) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.readinessProbe = Objects.requireNonNull(readinessProbe, "readinessProbe");
        this.startupPolicy = Objects.requireNonNull(startupPolicy, "startupPolicy");
    }

    public LocalClusterProcessGroup start(ClusterLaunchPlan plan) {
        Objects.requireNonNull(plan, "plan");
        List<ClusterProcess> started = new ArrayList<>();
        try {
            for (ClusterLaunchCommand command : plan.commands()) {
                ClusterProcess process = launcher.start(command);
                started.add(process);
                awaitReady(command, process);
            }
            return LocalClusterProcessGroup.of(started);
        } catch (Exception e) {
            LocalClusterProcessGroup.of(started).close();
            throw new IllegalStateException("Failed to start local cluster", e);
        }
    }

    private void awaitReady(ClusterLaunchCommand command, ClusterProcess process) throws InterruptedException {
        long timeoutNanos = startupPolicy.timeout().toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        while (true) {
            if (!process.alive()) {
                throw new IllegalStateException("Cluster process exited before ready: " + command.serviceName());
            }
            if (readinessProbe.ready(command, process)) {
                return;
            }
            if (timeoutNanos == 0 || System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out waiting for service ready: " + command.serviceName());
            }
            long sleepMillis = Math.max(1, Math.min(
                    startupPolicy.pollInterval().toMillis(),
                    startupPolicy.timeout().toMillis()
            ));
            Thread.sleep(sleepMillis);
        }
    }
}
