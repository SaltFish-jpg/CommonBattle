package com.commonbattle.game.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 跨服版本事件的消费端持久化收件箱。
 * 实现必须先可靠保存事件再返回 APPENDED，并保证同一 EventInboxKey 的重复写入幂等。
 */
public interface VersionedEventInbox {
    EventInboxAppendStatus appendIfAbsent(VersionedEvent event, Instant receivedAt);

    Optional<PendingEventInboxEntry> nextReady(String topic, String ownerKey, Instant now);

    List<PendingEventInboxEntry> ready(String topic, Instant now, int limit);

    List<PendingEventInboxEntry> pending(String topic);

    List<DeadLetteredEventInboxEntry> deadLetters(String topic);

    void markHandled(EventInboxKey key);

    EventInboxFailureResult markAttemptFailed(
            EventInboxKey key,
            Throwable failure,
            Instant failedAt,
            Instant nextAttemptAt,
            int maxAttempts
    );

    boolean redriveDeadLetter(EventInboxKey key, Instant nextAttemptAt);
}
