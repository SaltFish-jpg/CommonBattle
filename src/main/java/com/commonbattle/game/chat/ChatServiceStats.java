package com.commonbattle.game.chat;

/**
 * Chat 服务运行统计。
 */
public record ChatServiceStats(long activeChannels, long joinRequests, long leaveRequests, long sendRequests) {
}
