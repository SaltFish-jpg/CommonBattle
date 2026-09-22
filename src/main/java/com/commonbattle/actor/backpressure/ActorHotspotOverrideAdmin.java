package com.commonbattle.actor.backpressure;

import java.time.Duration;
import java.util.List;

/**
 * Actor 热点准入的人工控制入口。
 * 运维面板或启动装配可通过它临时豁免、限流或标记迁移候选 Actor。
 */
public interface ActorHotspotOverrideAdmin {
    List<ActorHotspotOverride> hotspotOverrides();

    ActorHotspotOverride setHotspotOverride(
            String actorId,
            ActorHotspotOverrideMode mode,
            Duration ttl,
            String reason
    );

    int clearHotspotOverride(String actorId);
}
