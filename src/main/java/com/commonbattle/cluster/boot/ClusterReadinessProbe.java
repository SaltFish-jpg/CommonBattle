package com.commonbattle.cluster.boot;

/**
 * 集群服务启动探针。
 * 管理器每启动一个服务后调用探针，探针通过后才继续启动后续服务。
 */
@FunctionalInterface
public interface ClusterReadinessProbe {
    boolean ready(ClusterLaunchCommand command, ClusterProcess process);

    static ClusterReadinessProbe alwaysReady() {
        return (command, process) -> true;
    }
}
