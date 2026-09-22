package com.commonbattle.actor.agent.migration;

import com.commonbattle.observability.ActorHotspotCandidate;

import java.util.List;
import java.util.Objects;

/**
 * 默认热点迁移提交服务。
 * 它把热点候选交给计划器，并使用启动期注入的业务快照打包器生成迁移快照。
 */
public final class ActorHotspotMigrationSubmitService implements ActorHotspotMigrationAdmin {
    private final ActorHotspotMigrationPlanner planner;
    private final AgentMigrationStatePacker packer;

    public ActorHotspotMigrationSubmitService(
            ActorHotspotMigrationPlanner planner,
            AgentMigrationStatePacker packer
    ) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.packer = Objects.requireNonNull(packer, "packer");
    }

    @Override
    public List<ActorHotspotMigrationResult> submitHotspotMigrations(
            List<ActorHotspotCandidate> candidates,
            int maxSubmissions
    ) {
        return planner.submitCandidates(candidates, packer, maxSubmissions);
    }
}
