package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;

import java.util.Arrays;
import java.util.Objects;

/**
 * 目标服务接收迁移 Agent 的请求。
 * stateType/stateBytes 是业务自定义快照，框架只保证它随迁移协议被定点送达目标 Actor 邮箱。
 */
public record AgentMigrationAcceptRequest(
        String taskId,
        AgentIdentity identity,
        String actorId,
        String stateType,
        byte[] stateBytes
) {
    public AgentMigrationAcceptRequest(
            AgentIdentity identity,
            String actorId,
            String stateType,
            byte[] stateBytes
    ) {
        this("", identity, actorId, stateType, stateBytes);
    }

    public AgentMigrationAcceptRequest {
        taskId = Objects.requireNonNullElse(taskId, "");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(stateBytes, "stateBytes");
        if (actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        if (stateType.isBlank()) {
            throw new IllegalArgumentException("stateType must not be blank");
        }
        stateBytes = Arrays.copyOf(stateBytes, stateBytes.length);
    }

    @Override
    public byte[] stateBytes() {
        return Arrays.copyOf(stateBytes, stateBytes.length);
    }
}
