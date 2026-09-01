package com.commonbattle.cluster.network;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;

import java.util.Objects;

/**
 * 代理服转发器。
 * 代理服不理解业务 payload，只按信封里的最终 target 转发，保持业务服务之间的网络解耦。
 */
public final class ForwardingProxy implements ClusterMessageHandler {
    private final ClusterTransport transport;

    public ForwardingProxy(ClusterTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public void bind(ServiceDescriptor proxy) {
        transport.bind(proxy, this);
    }

    @Override
    public void onMessage(ClusterEnvelope envelope) {
        ServiceId target = envelope.target();
        transport.send(target, envelope);
    }
}
