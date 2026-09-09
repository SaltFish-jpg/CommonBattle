package com.commonbattle.game.social;

/**
 * 好友关系变更请求。
 * 好友列表 owner 来自目标 AgentIdentity，payload 只携带被添加或删除的玩家。
 */
public record FriendRelationRequest(long friendId) {
    public FriendRelationRequest {
        if (friendId <= 0) {
            throw new IllegalArgumentException("friendId must be positive");
        }
    }
}
