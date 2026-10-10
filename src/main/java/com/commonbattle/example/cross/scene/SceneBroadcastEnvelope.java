package com.commonbattle.example.cross.scene;

import java.util.Set;

/**
 * 一次 Scene 广播投递请求。
 */
public record SceneBroadcastEnvelope(Set<Long> recipients, SceneBroadcastEvent event) {
    public SceneBroadcastEnvelope {
        recipients = Set.copyOf(recipients);
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
    }
}
