package com.commonbattle.game.chat;

import com.commonbattle.game.scene.AllianceMembershipDecision;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;

import java.util.Objects;

/**
 * 基于本地联盟快照的联盟频道访问策略。
 * 快照缺失或 stale 时拒绝准入，避免非成员通过旧关系进入联盟频道。
 */
public final class AllianceAwareChatChannelAccessPolicy implements ChatChannelAccessPolicy {
    private final SceneAllianceAwarenessAgent alliances;

    public AllianceAwareChatChannelAccessPolicy(SceneAllianceAwarenessAgent alliances) {
        this.alliances = Objects.requireNonNull(alliances, "alliances");
    }

    @Override
    public ChatJoinStatus inspectJoin(ChatJoinRequest request) {
        long allianceId = allianceIdOf(request.channelId());
        if (allianceId == 0) {
            return ChatJoinStatus.JOINED;
        }
        return switch (alliances.membershipOf(allianceId, request.playerId())) {
            case MEMBER -> ChatJoinStatus.JOINED;
            case NOT_MEMBER -> ChatJoinStatus.NOT_ALLIANCE_MEMBER;
            case STALE -> ChatJoinStatus.STALE_ALLIANCE;
        };
    }

    @Override
    public ChatSendStatus inspectSend(ChatSendRequest request) {
        long allianceId = allianceIdOf(request.channelId());
        if (allianceId == 0) {
            return ChatSendStatus.SENT;
        }
        return switch (alliances.membershipOf(allianceId, request.senderId())) {
            case MEMBER -> ChatSendStatus.SENT;
            case NOT_MEMBER -> ChatSendStatus.NOT_ALLIANCE_MEMBER;
            case STALE -> ChatSendStatus.STALE_ALLIANCE;
        };
    }

    private static long allianceIdOf(String channelId) {
        if (!channelId.startsWith("alliance:")) {
            return 0;
        }
        return Long.parseLong(channelId.substring("alliance:".length()));
    }
}
