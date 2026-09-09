package com.commonbattle.game.chat;

/**
 * 加入联盟聊天请求。
 * 联盟频道按 allianceId 映射到独立频道 Actor。
 */
public record AllianceChatJoinRequest(long allianceId, long playerId) {
    public AllianceChatJoinRequest {
        if (allianceId <= 0) {
            throw new IllegalArgumentException("allianceId must be positive");
        }
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
    }
}
