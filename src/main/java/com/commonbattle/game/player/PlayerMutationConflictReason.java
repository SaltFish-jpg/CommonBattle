package com.commonbattle.game.player;

/**
 * 玩家原子提交冲突类型。
 */
public enum PlayerMutationConflictReason {
    OWNER_FENCED,
    STALE_REVISION,
    REVISION_GAP
}
