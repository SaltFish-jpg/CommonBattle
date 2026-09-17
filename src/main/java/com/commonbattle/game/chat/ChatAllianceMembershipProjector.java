package com.commonbattle.game.chat;

import com.commonbattle.game.scene.AllianceMembershipChangeListener;
import com.commonbattle.game.social.AllianceSnapshot;

import java.util.Objects;

/**
 * 把联盟成员关系变化投递给联盟聊天频道 Actor。
 * 它不直接修改频道状态，只负责把事件转换成频道 mailbox 内的收敛任务。
 */
public final class ChatAllianceMembershipProjector implements AllianceMembershipChangeListener {
    private final ChatChannelManager channels;

    public ChatAllianceMembershipProjector(ChatChannelManager channels) {
        this.channels = Objects.requireNonNull(channels, "channels");
    }

    @Override
    public void onAllianceMemberLeft(long allianceId, long playerId, long revision) {
        channels.removeAllianceMember(allianceId, playerId);
    }

    @Override
    public void onAllianceSnapshot(AllianceSnapshot snapshot) {
        channels.retainAllianceMembers(snapshot.allianceId(), snapshot.members());
    }
}
