package com.commonbattle.game.event;

import java.time.Instant;

/**
 * outbox 中等待投递或等待确认的版本事件。
 */
public record PendingVersionedEvent(long id, VersionedEvent event, Instant createdAt, int attempts) {
}
