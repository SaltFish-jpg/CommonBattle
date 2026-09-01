package com.commonbattle.cluster.network;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;

/**
 * 跨服传输层抽象。
 * Netty、内存总线或测试桩都只需要实现绑定本地服务、发送信封和关闭连接。
 */
public interface ClusterTransport extends AutoCloseable {
    void bind(ServiceDescriptor local, ClusterMessageHandler handler);

    void send(ServiceId nextHop, ClusterEnvelope envelope);
}
