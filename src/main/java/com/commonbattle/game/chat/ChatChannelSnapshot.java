package com.commonbattle.game.chat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 频道 Actor 的只读快照，用于测试、运维诊断和后续接入管理后台。
 */
public record ChatChannelSnapshot(
        String channelId,
        Set<Long> members,
        List<ChatDelivery> history,
        long revision,
        Map<Long, Long> memberAllianceIds
) {
    public ChatChannelSnapshot {
        channelId = ChatJoinRequest.normalizeChannelId(channelId);
        members = Set.copyOf(members);
        history = List.copyOf(history);
        memberAllianceIds = Map.copyOf(Objects.requireNonNull(memberAllianceIds, "memberAllianceIds"));
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        for (Map.Entry<Long, Long> entry : memberAllianceIds.entrySet()) {
            if (!members.contains(entry.getKey())) {
                throw new IllegalArgumentException("member alliance does not belong to the channel");
            }
            if (entry.getValue() < 0) {
                throw new IllegalArgumentException("member alliance id must not be negative");
            }
        }
    }

    public ChatChannelSnapshot(String channelId, Set<Long> members, List<ChatDelivery> history, long revision) {
        this(channelId, members, history, revision, Map.of());
    }
}
