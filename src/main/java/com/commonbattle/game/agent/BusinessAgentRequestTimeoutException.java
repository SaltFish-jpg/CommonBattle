package com.commonbattle.game.agent;

import com.commonbattle.actor.agent.AgentIdentity;

import java.time.Duration;
import java.util.Objects;

/**
 * 业务 Agent 远端请求超时。
 * 表示调用方已经在自己的 mailbox 中收到失败回调，迟到的 RPC 回包会被丢弃。
 */
public final class BusinessAgentRequestTimeoutException extends RuntimeException {
    private final AgentIdentity target;
    private final String operation;
    private final Duration timeout;

    public BusinessAgentRequestTimeoutException(AgentIdentity target, String operation, Duration timeout) {
        super("Business agent request timeout, target=" + Objects.requireNonNull(target, "target")
                + ", operation=" + Objects.requireNonNull(operation, "operation")
                + ", timeout=" + Objects.requireNonNull(timeout, "timeout"));
        this.target = target;
        this.operation = operation;
        this.timeout = timeout;
    }

    public AgentIdentity target() {
        return target;
    }

    public String operation() {
        return operation;
    }

    public Duration timeout() {
        return timeout;
    }
}
