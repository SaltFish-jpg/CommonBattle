package com.commonbattle.game.social;

/**
 * 联盟成员变更请求。
 * 联盟 owner 来自目标 AgentIdentity，payload 只携带加入或退出的玩家。
 */
public record AllianceMemberRequest(long playerId) {
    public AllianceMemberRequest {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
    }
}
