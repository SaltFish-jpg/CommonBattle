package com.commonbattle.game.scene;

/**
 * 场景玩家兴趣生命周期统计。
 */
public record ScenePlayerInterestStats(
        int players,
        int interests,
        int allianceReferences,
        long enters,
        long duplicateEnters,
        long leaves,
        long missingLeaves,
        long allianceSwitches
) {
}
