package com.commonbattle.cluster.event;

import com.commonbattle.game.event.VersionedEvent;

/**
 * 事件中心推送给订阅服务的版本事件。
 */
public record EventDeliverRequest(VersionedEvent event) {
    public EventDeliverRequest() {
        this(null);
    }
}
