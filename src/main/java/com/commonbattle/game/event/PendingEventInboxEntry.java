package com.commonbattle.game.event;

import java.time.Instant;

/**
 * 等待进入 Actor 邮箱或等待重试的消费端事件。
 */
public record PendingEventInboxEntry(
        EventInboxKey key,
        VersionedEvent event,
        Instant receivedAt,
        int attempts,
        Instant nextAttemptAt,
        String lastFailure
) {
}
