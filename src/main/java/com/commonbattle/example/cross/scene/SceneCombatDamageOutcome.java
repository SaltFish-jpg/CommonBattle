package com.commonbattle.example.cross.scene;

/**
 * Scene shard 内一次伤害同步结果。
 */
public record SceneCombatDamageOutcome(
        String sceneId,
        long attackerId,
        long targetId,
        int shardIndex,
        int damage,
        int hpBefore,
        int hpAfter,
        boolean applied
) {
    public static SceneCombatDamageOutcome rejected(String sceneId, long attackerId, long targetId) {
        return new SceneCombatDamageOutcome(sceneId, attackerId, targetId, -1, 0, 0, 0, false);
    }
}
