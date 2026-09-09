package com.commonbattle.game.agent;

import com.commonbattle.actor.agent.AgentIdentity;

import java.util.Objects;

/**
 * 通用业务 Agent 请求失败。
 * reason 使用稳定字符串，便于上层把缺失 owner、迁移中和未知操作映射成可观测的业务错误。
 */
public final class BusinessAgentRequestException extends RuntimeException {
    private final AgentIdentity target;
    private final String operation;
    private final String reason;

    public BusinessAgentRequestException(AgentIdentity target, String operation, String reason) {
        super("Business agent request failed: target=" + Objects.requireNonNull(target, "target").wireName()
                + ", operation=" + Objects.requireNonNull(operation, "operation")
                + ", reason=" + Objects.requireNonNull(reason, "reason"));
        this.target = target;
        this.operation = operation;
        this.reason = reason;
    }

    public AgentIdentity target() {
        return target;
    }

    public String operation() {
        return operation;
    }

    public String reason() {
        return reason;
    }
}
