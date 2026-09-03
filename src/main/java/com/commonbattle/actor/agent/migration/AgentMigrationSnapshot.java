package com.commonbattle.actor.agent.migration;

import java.util.Arrays;
import java.util.Objects;

/**
 * Agent 迁移快照。
 * 业务可按 stateType 做版本分发，框架只把字节载荷可靠送到目标恢复扩展点。
 */
public record AgentMigrationSnapshot(String stateType, byte[] stateBytes) {
    public AgentMigrationSnapshot {
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(stateBytes, "stateBytes");
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
