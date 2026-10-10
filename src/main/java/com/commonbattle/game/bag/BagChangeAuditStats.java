package com.commonbattle.game.bag;

/**
 * 背包变更流水统计。
 */
public record BagChangeAuditStats(
        int retained,
        long recorded,
        long increased,
        long decreased,
        long unchanged,
        long dropped
) {
}
