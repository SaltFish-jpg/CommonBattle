package com.commonbattle.game.chat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chat 访问控制状态。
 * 示例实现为进程内内存结构，生产环境可替换为配置中心、GM 指令或风控服务驱动。
 */
public final class ChatAccessControl {
    private final Set<Long> mutedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> blockedChannels = ConcurrentHashMap.newKeySet();
    private final AtomicLong mutedRejects = new AtomicLong();
    private final AtomicLong blockedRejects = new AtomicLong();

    public void mute(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        mutedPlayers.add(playerId);
    }

    public void unmute(long playerId) {
        mutedPlayers.remove(playerId);
    }

    public void blockChannel(String channelId) {
        blockedChannels.add(ChatJoinRequest.normalizeChannelId(channelId));
    }

    public void unblockChannel(String channelId) {
        blockedChannels.remove(ChatJoinRequest.normalizeChannelId(channelId));
    }

    ChatSendStatus inspect(ChatSendRequest request) {
        if (mutedPlayers.contains(request.senderId())) {
            mutedRejects.incrementAndGet();
            return ChatSendStatus.MUTED;
        }
        if (blockedChannels.contains(request.channelId())) {
            blockedRejects.incrementAndGet();
            return ChatSendStatus.BLOCKED;
        }
        return ChatSendStatus.SENT;
    }

    public long mutedRejects() {
        return mutedRejects.get();
    }

    public long blockedRejects() {
        return blockedRejects.get();
    }
}
