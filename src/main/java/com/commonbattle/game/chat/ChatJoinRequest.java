package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * 加入频道请求。
 * Game、Scene 等服务通过 RPC 发到 Chat 服，Chat 服只把它投递给频道 Actor。
 */
public record ChatJoinRequest(String channelId, long playerId, long allianceId) {
    public ChatJoinRequest {
        channelId = normalizeChannelId(channelId);
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (allianceId < 0) {
            throw new IllegalArgumentException("allianceId must not be negative");
        }
    }

    static String normalizeChannelId(String channelId) {
        Objects.requireNonNull(channelId, "channelId");
        String normalized = channelId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("channelId must not be blank");
        }
        return normalized;
    }
}
