package com.commonbattle.game.social;

import com.commonbattle.game.event.VersionedEvent;

/**
 * 联盟成员加入或退出事件。
 * allianceId 是关系 owner，revision 是该联盟成员列表的单调版本。
 */
public record AllianceMemberChangedEvent(
        long allianceId,
        long playerId,
        AllianceMemberAction action,
        long revision
) implements VersionedEvent {
    public static final String TOPIC = "alliance.member.changed";

    public AllianceMemberChangedEvent() {
        this(0, 0, AllianceMemberAction.JOIN, 0);
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String ownerKey() {
        return AllianceOwnerKeyParser.ownerKey(allianceId);
    }
}
