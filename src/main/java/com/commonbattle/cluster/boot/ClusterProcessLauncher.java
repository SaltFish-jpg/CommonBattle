package com.commonbattle.cluster.boot;

/**
 * 集群服务进程启动器。
 * 本地开发可用真实 ProcessBuilder，集成测试可替换成记录型启动器。
 */
@FunctionalInterface
public interface ClusterProcessLauncher {
    ClusterProcess start(ClusterLaunchCommand command) throws Exception;
}
