package com.commonbattle.cluster.network;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;

/**
 * 跨服传输层抽象。
 * Netty、内存总线或测试桩都只需要实现绑定本地服务、发送信封和关闭连接。
 */
public interface ClusterTransport extends AutoCloseable {
    void bind(ServiceDescriptor local, ClusterMessageHandler handler);

    /**
     * 接受一个待发送信封；异步传输的最终失败只进入自身统计。
     * 需要可靠感知结果的框架层应使用带 ClusterSendCallback 的重载。
     */
    void send(ServiceId nextHop, ClusterEnvelope envelope);

    /**
     * 异步发送信封并报告最终写入结果。
     * 自定义传输若没有异步能力，可以沿用该默认实现。
     */
    default void send(ServiceId nextHop, ClusterEnvelope envelope, ClusterSendCallback callback) {
        try {
            send(nextHop, envelope);
        } catch (RuntimeException error) {
            callback.failure(error);
            return;
        }
        callback.success();
    }
}
