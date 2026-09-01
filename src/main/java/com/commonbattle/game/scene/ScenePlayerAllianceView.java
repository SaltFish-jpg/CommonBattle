package com.commonbattle.game.scene;

/**
 * 场景内玩家联盟只读视图。
 */
public record ScenePlayerAllianceView(long playerId, long allianceId, long revision, boolean stale) {
}
