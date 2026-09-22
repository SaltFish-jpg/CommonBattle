package com.commonbattle.example.cross.scene;

import java.util.Set;

/**
 * 小场景 Agent 迁移快照。
 * 记录场景身份和当前在场玩家，目标 Scene 服恢复后继续由同一个 scene mailbox 串行处理。
 */
public record SmallSceneAgentSnapshot(String sceneId, Set<Long> players) {
    public SmallSceneAgentSnapshot {
        if (sceneId == null || sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        players = Set.copyOf(players);
    }
}
