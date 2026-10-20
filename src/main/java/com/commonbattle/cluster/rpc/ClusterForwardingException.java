package com.commonbattle.cluster.rpc;

import com.commonbattle.cluster.ServiceId;

import java.util.Objects;

/**
 * 代理服已收到信封，但无法继续投递到最终目标。
 * 调用方可立即重试到其它代理，不必等待整次 RPC 超时。
 */
public final class ClusterForwardingException extends RuntimeException {
    private final ServiceId proxy;
    private final ServiceId failedTarget;
    private final String failedOperation;

    public ClusterForwardingException(
            ServiceId proxy,
            ServiceId failedTarget,
            String failedOperation,
            String reason
    ) {
        super("Proxy " + Objects.requireNonNull(proxy, "proxy").wireName()
                + " failed to forward " + Objects.requireNonNull(failedOperation, "failedOperation")
                + " to " + Objects.requireNonNull(failedTarget, "failedTarget").wireName()
                + (reason == null || reason.isBlank() ? "" : ": " + reason));
        this.proxy = proxy;
        this.failedTarget = failedTarget;
        this.failedOperation = failedOperation;
    }

    public ServiceId proxy() {
        return proxy;
    }

    public ServiceId failedTarget() {
        return failedTarget;
    }

    public String failedOperation() {
        return failedOperation;
    }
}
