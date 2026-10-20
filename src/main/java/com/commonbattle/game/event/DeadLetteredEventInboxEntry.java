package com.commonbattle.game.event;

import java.time.Instant;

/**
 * Actor 消费连续失败后被隔离的事件。
 * 同 owner 的后续 revision 会保持阻塞，直到该死信被重放或通过快照修复后显式释放。
 */
public record DeadLetteredEventInboxEntry(
        EventInboxKey key,
        VersionedEvent event,
        Instant receivedAt,
        int attempts,
        Instant deadLetteredAt,
        String lastFailure
) {
}
