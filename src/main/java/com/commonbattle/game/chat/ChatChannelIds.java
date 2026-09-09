package com.commonbattle.game.chat;

/**
 * Chat 业务实体到频道 Actor 的命名规则。
 */
public final class ChatChannelIds {
    private ChatChannelIds() {
    }

    public static String world(String worldId, int shard) {
        if (shard < 0) {
            throw new IllegalArgumentException("shard must not be negative");
        }
        return "world:" + WorldChatJoinRequest.normalizeWorldId(worldId) + ":" + shard;
    }

    public static String alliance(long allianceId) {
        if (allianceId <= 0) {
            throw new IllegalArgumentException("allianceId must be positive");
        }
        return "alliance:" + allianceId;
    }

    public static String direct(long firstPlayerId, long secondPlayerId) {
        long first = Math.min(firstPlayerId, secondPlayerId);
        long second = Math.max(firstPlayerId, secondPlayerId);
        if (first <= 0 || second <= 0 || first == second) {
            throw new IllegalArgumentException("direct players must be two positive ids");
        }
        return "direct:" + first + ":" + second;
    }
}
