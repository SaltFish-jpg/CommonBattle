package com.commonbattle.cluster.network;

import com.commonbattle.cluster.ServiceId;

import java.util.Objects;

/**
 * 跨服信封在建连或写入阶段失败。
 * 上层可将它视为可重试的基础设施故障，但带副作用的 RPC 仍需携带幂等键。
 */
public final class ClusterTransportException extends RuntimeException {
    private final ServiceId nextHop;

    public ClusterTransportException(ServiceId nextHop, String message) {
        super(message);
        this.nextHop = Objects.requireNonNull(nextHop, "nextHop");
    }

    public ClusterTransportException(ServiceId nextHop, String message, Throwable cause) {
        super(message, cause);
        this.nextHop = Objects.requireNonNull(nextHop, "nextHop");
    }

    public ServiceId nextHop() {
        return nextHop;
    }
}
