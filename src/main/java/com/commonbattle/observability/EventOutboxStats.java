package com.commonbattle.observability;

/**
 * 版本事件 outbox 堆积统计。
 */
public record EventOutboxStats(int pendingEvents, int failedAttempts, long oldestPendingAgeMillis) {
}
