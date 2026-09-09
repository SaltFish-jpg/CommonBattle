package com.commonbattle.game.agent;

import com.commonbattle.actor.agent.AgentIdentity;

import java.util.Objects;

/**
 * 通用业务 Agent 请求。
 * 用于联盟、好友、场景、商店库存等非玩家命令类调用，目标服务端收到后仍必须投递到 owner Actor 邮箱执行。
 */
public record BusinessAgentRequest(
        AgentIdentity target,
        String operation,
        Object payload
) {
    public BusinessAgentRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
    }
}
