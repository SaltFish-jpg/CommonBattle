package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * 加入世界聊天请求。
 * worldId 通常对应大区或跨服分组，实际承载会按玩家维度分片到多个频道 Actor。
 */
public record WorldChatJoinRequest(String worldId, long playerId, long allianceId) {
    public WorldChatJoinRequest {
        worldId = normalizeWorldId(worldId);
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (allianceId < 0) {
            throw new IllegalArgumentException("allianceId must not be negative");
        }
    }

    static String normalizeWorldId(String worldId) {
        Objects.requireNonNull(worldId, "worldId");
        String normalized = worldId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("worldId must not be blank");
        }
        return normalized;
    }
}
