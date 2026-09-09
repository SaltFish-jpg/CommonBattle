package com.commonbattle.game.chat;

/**
 * 离开世界聊天请求。
 */
public record WorldChatLeaveRequest(String worldId, long playerId) {
    public WorldChatLeaveRequest {
        worldId = WorldChatJoinRequest.normalizeWorldId(worldId);
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
    }
}
