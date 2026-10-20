package com.commonbattle.game.player;

import java.util.Map;
import java.util.Objects;

/**
 * 玩家异步业务命令治理策略。
 * 用于限制单个玩家 Agent 内等待外部回调的命令数量，避免某类玩法依赖卡死后长期阻塞保存和迁移边界。
 */
public record AsyncBusinessCommandPolicy(
        int maxTotalPendingCommands,
        Map<String, Integer> maxPendingByOperation
) {
    public static final int UNLIMITED = Integer.MAX_VALUE;
    private static final AsyncBusinessCommandPolicy UNLIMITED_POLICY =
            new AsyncBusinessCommandPolicy(UNLIMITED, Map.of());

    public AsyncBusinessCommandPolicy {
        Objects.requireNonNull(maxPendingByOperation, "maxPendingByOperation");
        if (maxTotalPendingCommands <= 0) {
            throw new IllegalArgumentException("maxTotalPendingCommands must be positive");
        }
        maxPendingByOperation.forEach((operation, maxPending) -> {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("operation must not be blank");
            }
            if (maxPending == null || maxPending <= 0) {
                throw new IllegalArgumentException("maxPending must be positive");
            }
        });
        maxPendingByOperation = Map.copyOf(maxPendingByOperation);
    }

    public static AsyncBusinessCommandPolicy unlimited() {
        return UNLIMITED_POLICY;
    }

    public static AsyncBusinessCommandPolicy maxPendingForOperation(String operation, int maxPending) {
        return new AsyncBusinessCommandPolicy(UNLIMITED, Map.of(operation, maxPending));
    }

    public boolean allows(String operation, int totalPendingCommands, int operationPendingCommands) {
        return totalPendingCommands < maxTotalPendingCommands
                && operationPendingCommands < maxPendingByOperation.getOrDefault(operation, UNLIMITED);
    }

    public int maxPendingFor(String operation) {
        return Math.min(maxTotalPendingCommands, maxPendingByOperation.getOrDefault(operation, UNLIMITED));
    }
}
