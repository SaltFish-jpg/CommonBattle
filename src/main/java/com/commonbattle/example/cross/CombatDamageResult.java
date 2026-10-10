package com.commonbattle.example.cross;

/**
 * Scene shard 伤害同步回包。
 */
public record CombatDamageResult(
        String sceneId,
        long attackerId,
        long targetId,
        int shardIndex,
        int damage,
        int hpBefore,
        int hpAfter,
        boolean applied
) {
}
