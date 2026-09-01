package com.commonbattle.game.profile;

/**
 * 好友关系摘要。
 * 通用玩家资料只保存轻量统计和版本，完整好友列表应由好友 owner 或专门社交缓存提供。
 */
public record FriendBrief(int friendCount, long socialRevision) {
    public FriendBrief() {
        this(0, 0);
    }
}
