package com.commonbattle.game.chat;

import com.commonbattle.game.scene.AllianceMembershipDecision;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.social.AllianceMemberChangedEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 基于本地联盟快照的联盟频道访问策略。
 * 快照缺失或 stale 时拒绝准入，避免非成员通过旧关系进入联盟频道。
 */
public final class AllianceAwareChatChannelAccessPolicy implements EventAwareChatChannelAccessPolicy {
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
        return joinStatusOf(alliances.membershipOf(allianceId, request.playerId()));
    }

    @Override
    public ChatSendStatus inspectSend(ChatSendRequest request) {
        long allianceId = allianceIdOf(request.channelId());
        if (allianceId == 0) {
            return ChatSendStatus.SENT;
        }
        return sendStatusOf(alliances.membershipOf(allianceId, request.senderId()));
    }

    @Override
    public void inspectJoinAfterAllianceChanged(
            AllianceMemberChangedEvent event,
            ChatJoinRequest request,
            Consumer<ChatJoinStatus> callback
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        long allianceId = allianceIdOf(request.channelId());
        if (allianceId != event.allianceId() || request.playerId() != event.playerId()) {
            callback.accept(ChatJoinStatus.STALE_ALLIANCE);
            return;
        }
        alliances.onAllianceChangedAndReadFreshMembership(event, decision ->
                callback.accept(joinStatusOf(decision)));
    }

    @Override
    public void inspectSendAfterAllianceChanged(
            AllianceMemberChangedEvent event,
            ChatSendRequest request,
            Consumer<ChatSendStatus> callback
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        long allianceId = allianceIdOf(request.channelId());
        if (allianceId != event.allianceId() || request.senderId() != event.playerId()) {
            callback.accept(ChatSendStatus.STALE_ALLIANCE);
            return;
        }
        alliances.onAllianceChangedAndReadFreshMembership(event, decision ->
                callback.accept(sendStatusOf(decision)));
    }

    private static ChatJoinStatus joinStatusOf(AllianceMembershipDecision decision) {
        return switch (decision) {
            case MEMBER -> ChatJoinStatus.JOINED;
            case NOT_MEMBER -> ChatJoinStatus.NOT_ALLIANCE_MEMBER;
            case STALE -> ChatJoinStatus.STALE_ALLIANCE;
        };
    }

    private static ChatSendStatus sendStatusOf(AllianceMembershipDecision decision) {
        return switch (decision) {
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
