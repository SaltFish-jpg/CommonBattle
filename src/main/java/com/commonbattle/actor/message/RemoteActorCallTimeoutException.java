package com.commonbattle.actor.message;

import com.commonbattle.actor.rpc.RpcRequest;

import java.time.Duration;
import java.util.Objects;

/**
 * 远程 Actor 调用在调用侧等待超时。
 */
public final class RemoteActorCallTimeoutException extends RuntimeException {
    private final RpcRequest<?> request;
    private final Duration timeout;

    public RemoteActorCallTimeoutException(RpcRequest<?> request, Duration timeout) {
        super("Remote actor call timed out after " + Objects.requireNonNull(timeout, "timeout")
                + ": " + Objects.requireNonNull(request, "request").operation());
        this.request = request;
        this.timeout = timeout;
    }

    public RpcRequest<?> request() {
        return request;
    }

    public Duration timeout() {
        return timeout;
    }
}
