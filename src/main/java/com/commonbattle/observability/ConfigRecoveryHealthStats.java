package com.commonbattle.observability;

/**
 * 配置自动恢复健康统计。
 * requested 表示触发恢复次数，skippedWhileInFlight 表示已有恢复在飞时被合并掉的触发次数。
 */
public record ConfigRecoveryHealthStats(
        int recoveryCount,
        int requested,
        int skippedWhileInFlight,
        int succeeded,
        int failed,
        int inFlight
) {
    public ConfigRecoveryHealthStats {
        if (recoveryCount < 0 || requested < 0 || skippedWhileInFlight < 0
                || succeeded < 0 || failed < 0 || inFlight < 0) {
            throw new IllegalArgumentException("recovery stats must not be negative");
        }
    }

    public static ConfigRecoveryHealthStats empty() {
        return new ConfigRecoveryHealthStats(0, 0, 0, 0, 0, 0);
    }
}
