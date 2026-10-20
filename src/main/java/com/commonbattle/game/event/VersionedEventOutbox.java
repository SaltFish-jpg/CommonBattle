package com.commonbattle.game.event;

import java.time.Instant;
import java.util.List;

/**
 * 版本事件 outbox。
 * 状态 owner 在修改后先把事件写入 outbox，再尝试发布；发布失败时由后台任务重放 pending 事件。
 */
public interface VersionedEventOutbox {
    PendingVersionedEvent append(VersionedEvent event);

    List<PendingVersionedEvent> pending();

    default List<PendingVersionedEvent> ready(Instant now, int limit) {
        return pending().stream()
                .filter(entry -> !entry.nextAttemptAt().isAfter(now))
                .limit(limit)
                .toList();
    }

    default List<DeadLetteredVersionedEvent> deadLetters() {
        return List.of();
    }

    void markPublished(long outboxId);

    default OutboxFailureResult markAttemptFailed(
            long outboxId,
            Throwable failure,
            Instant failedAt,
            Instant nextAttemptAt,
            int maxAttempts
    ) {
        markAttemptFailed(outboxId);
        return OutboxFailureResult.RETRY_SCHEDULED;
    }

    /**
     * 兼容旧实现的立即重试标记；新代码应传入失败原因和退避时间。
     */
    void markAttemptFailed(long outboxId);

    default boolean redriveDeadLetter(long outboxId, Instant nextAttemptAt) {
        return false;
    }
}
