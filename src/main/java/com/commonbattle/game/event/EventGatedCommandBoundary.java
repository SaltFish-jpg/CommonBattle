package com.commonbattle.game.event;

import java.util.Objects;

/**
 * 事件门控命令的一致性边界。
 * 调用方用 topic/ownerKey/revision 定位必须追到的状态版本，再把业务命令交给 Actor 串行执行。
 */
public record EventGatedCommandBoundary(
        String topic,
        String ownerKey,
        long revision
) {
    public EventGatedCommandBoundary {
        topic = Objects.requireNonNull(topic, "topic");
        ownerKey = Objects.requireNonNull(ownerKey, "ownerKey");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }

    public static EventGatedCommandBoundary from(VersionedEvent event) {
        Objects.requireNonNull(event, "event");
        return new EventGatedCommandBoundary(event.topic(), event.ownerKey(), event.revision());
    }
}
