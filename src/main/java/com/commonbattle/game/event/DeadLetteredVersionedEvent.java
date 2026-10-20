package com.commonbattle.game.event;

import java.time.Instant;

/**
 * 超过最大重试次数后隔离的版本事件。
 * 运维确认下游恢复或修复毒消息后，可按 outboxId 重新投入补发队列。
 */
public record DeadLetteredVersionedEvent(
        long id,
        VersionedEvent event,
        Instant createdAt,
        int attempts,
        Instant deadLetteredAt,
        String lastFailure
) {
}
