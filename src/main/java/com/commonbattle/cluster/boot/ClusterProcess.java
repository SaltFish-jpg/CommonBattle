package com.commonbattle.cluster.boot;

/**
 * 本地集群中的一个已启动服务进程。
 * 启动器可以包装真实 Java Process，也可以在测试中提供内存句柄。
 */
public interface ClusterProcess extends AutoCloseable {
    String serviceName();

    boolean alive();

    @Override
    void close();
}
