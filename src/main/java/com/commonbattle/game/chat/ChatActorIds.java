package com.commonbattle.game.chat;

import com.commonbattle.actor.agent.AgentIdentity;

/**
 * Chat 业务实体到 ActorId 和准入身份的命名规则。
 */
public final class ChatActorIds {
    public static final String CHANNEL_TYPE = "chat-channel";
    public static final String DIRECT_TYPE = "chat-direct";

    private ChatActorIds() {
    }

    public static AgentIdentity channelIdentity(String channelId) {
        return new AgentIdentity(CHANNEL_TYPE, ChatJoinRequest.normalizeChannelId(channelId));
    }

    public static String channelActorId(String channelId) {
        return "chat-channel-" + ChatJoinRequest.normalizeChannelId(channelId);
    }

    public static AgentIdentity directIdentity(long firstPlayerId, long secondPlayerId) {
        return new AgentIdentity(DIRECT_TYPE, ChatChannelIds.direct(firstPlayerId, secondPlayerId));
    }

    public static String directActorId(long firstPlayerId, long secondPlayerId) {
        return directActorId(ChatChannelIds.direct(firstPlayerId, secondPlayerId));
    }

    public static String directActorId(String sessionId) {
        return "chat-" + sessionId;
    }

    public static String actorIdOf(AgentIdentity identity) {
        return switch (identity.type()) {
            case CHANNEL_TYPE -> channelActorId(identity.key());
            case DIRECT_TYPE -> directActorId(identity.key());
            default -> identity.wireName();
        };
    }
}
