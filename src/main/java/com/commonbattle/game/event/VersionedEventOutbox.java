package com.commonbattle.game.event;

import java.util.List;

/**
 * 版本事件 outbox。
 * 状态 owner 在修改后先把事件写入 outbox，再尝试发布；发布失败时由后台任务重放 pending 事件。
 */
public interface VersionedEventOutbox {
    PendingVersionedEvent append(VersionedEvent event);

    List<PendingVersionedEvent> pending();

    void markPublished(long outboxId);

    void markAttemptFailed(long outboxId);
}
