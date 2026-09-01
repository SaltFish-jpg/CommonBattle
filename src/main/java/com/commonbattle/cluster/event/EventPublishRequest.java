package com.commonbattle.cluster.event;

import com.commonbattle.game.event.VersionedEvent;

/**
 * 发布给事件中心的版本事件。
 */
public record EventPublishRequest(VersionedEvent event) {
    public EventPublishRequest() {
        this(null);
    }
}
