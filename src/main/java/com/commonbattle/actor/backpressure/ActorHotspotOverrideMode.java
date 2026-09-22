package com.commonbattle.actor.backpressure;

/**
 * Actor 热点准入的人工覆盖模式。
 * 用于线上临时处置单个热点 Actor，不改变 Actor 自身状态。
 */
public enum ActorHotspotOverrideMode {
    EXEMPT,
    THROTTLE,
    MIGRATION_CANDIDATE
}
