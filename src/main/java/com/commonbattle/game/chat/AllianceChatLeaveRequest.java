package com.commonbattle.game.chat;

/**
 * 离开联盟聊天请求。
 */
public record AllianceChatLeaveRequest(long allianceId, long playerId) {
    public AllianceChatLeaveRequest {
        if (allianceId <= 0) {
            throw new IllegalArgumentException("allianceId must be positive");
        }
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
    }
}
