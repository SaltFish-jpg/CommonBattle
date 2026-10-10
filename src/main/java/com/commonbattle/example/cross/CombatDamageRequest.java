package com.commonbattle.example.cross;

/**
 * Game/玩家 Agent 请求 Scene shard 执行一次轻量伤害同步。
 */
public record CombatDamageRequest(String sceneId, long attackerId, long targetId, int damage) {
    public CombatDamageRequest {
        if (sceneId == null || sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        if (damage <= 0) {
            throw new IllegalArgumentException("damage must be positive");
        }
    }
}
