package com.commonbattle.cluster.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 本地启动的一组集群进程。
 * 关闭时按启动的反向顺序停止，确保依赖方先退、基础服务最后退。
 */
public final class LocalClusterProcessGroup implements AutoCloseable {
    private final List<ClusterProcess> processes;

    LocalClusterProcessGroup(List<ClusterProcess> processes) {
        this.processes = List.copyOf(processes);
    }

    public List<ClusterProcess> processes() {
        return processes;
    }

    public long aliveCount() {
        return processes.stream().filter(ClusterProcess::alive).count();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        List<ClusterProcess> reversed = new ArrayList<>(processes);
        java.util.Collections.reverse(reversed);
        for (ClusterProcess process : reversed) {
            try {
                process.close();
            } catch (RuntimeException e) {
                RuntimeException wrapped = new IllegalStateException(
                        "Failed to close cluster process: " + process.serviceName(),
                        e
                );
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    static LocalClusterProcessGroup of(List<ClusterProcess> processes) {
        Objects.requireNonNull(processes, "processes");
        return new LocalClusterProcessGroup(processes);
    }
}
