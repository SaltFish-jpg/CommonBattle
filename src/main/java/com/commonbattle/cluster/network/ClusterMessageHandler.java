package com.commonbattle.cluster.network;

/**
 * 跨服传输层收到消息后的处理入口。
 */
@FunctionalInterface
public interface ClusterMessageHandler {
    void onMessage(ClusterEnvelope envelope);
}
