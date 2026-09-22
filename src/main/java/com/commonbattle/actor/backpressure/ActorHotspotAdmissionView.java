package com.commonbattle.actor.backpressure;

/**
 * Actor 热点动态准入控制只读视图。
 */
public interface ActorHotspotAdmissionView {
    ActorHotspotAdmissionStats hotspotAdmissionStats();
}
