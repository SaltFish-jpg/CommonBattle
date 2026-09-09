package com.commonbattle.game.chat;

import java.util.List;
import java.util.Set;

/**
 * 频道 Actor 的只读快照，用于测试、运维诊断和后续接入管理后台。
 */
public record ChatChannelSnapshot(String channelId, Set<Long> members, List<ChatDelivery> history, long revision) {
    public ChatChannelSnapshot {
        channelId = ChatJoinRequest.normalizeChannelId(channelId);
        members = Set.copyOf(members);
        history = List.copyOf(history);
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
