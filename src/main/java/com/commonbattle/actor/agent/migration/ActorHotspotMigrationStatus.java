package com.commonbattle.actor.agent.migration;

/**
 * Actor 热点迁移计划结果。
 * 状态用于区分是成功提交，还是因为路由、任务、目标选择等前置条件没有满足。
 */
public enum ActorHotspotMigrationStatus {
    SUBMITTED,
    NOT_MIGRATION_CANDIDATE,
    UNSUPPORTED_ACTOR_ID,
    SOURCE_NOT_FOUND,
    ALREADY_PENDING,
    NO_TARGET,
    SUBMIT_REJECTED,
    SUBMIT_FAILED
}
