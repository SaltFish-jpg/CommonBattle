package com.commonbattle.game.event;

/**
 * 可靠版本事件发布器运行统计。
 */
public record ReliableVersionedEventPublisherStats(
        long appended,
        long dispatchAttempts,
        long confirmed,
        long failedAttempts,
        long deadLettered,
        int inFlight
) {
}
