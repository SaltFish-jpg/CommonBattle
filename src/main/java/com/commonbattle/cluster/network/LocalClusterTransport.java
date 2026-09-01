package com.commonbattle.cluster.network;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存跨服传输。
 * 用于测试路由和代理转发语义，和 Netty 传输共享同一套 ClusterEnvelope。
 */
public final class LocalClusterTransport implements ClusterTransport {
    private final Map<ServiceId, ClusterMessageHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public void bind(ServiceDescriptor local, ClusterMessageHandler handler) {
        handlers.put(Objects.requireNonNull(local, "local").id(), Objects.requireNonNull(handler, "handler"));
    }

    @Override
    public void send(ServiceId nextHop, ClusterEnvelope envelope) {
        ClusterMessageHandler handler = handlers.get(Objects.requireNonNull(nextHop, "nextHop"));
        if (handler == null) {
            throw new IllegalStateException("No local transport handler for " + nextHop.wireName());
        }
        handler.onMessage(Objects.requireNonNull(envelope, "envelope"));
    }

    @Override
    public void close() {
        handlers.clear();
    }
}
