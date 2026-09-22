package com.commonbattle.actor.agent.migration;

import com.commonbattle.observability.ActorHotspotCandidate;

import java.util.List;

/**
 * 热点 Actor 迁移提交入口。
 * 运维端或后台调度器把候选列表交给它，具体提交、快照打包和目标选择由实现类处理。
 */
public interface ActorHotspotMigrationAdmin {
    List<ActorHotspotMigrationResult> submitHotspotMigrations(
            List<ActorHotspotCandidate> candidates,
            int maxSubmissions
    );
}
