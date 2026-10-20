package com.commonbattle.actor.agent;

/**
 * 本进程 Agent owner 租约监督统计。
 */
public record AgentLeaseSupervisorStats(
        int activeLeases,
        long acquiredLeases,
        long renewedLeases,
        long renewalFailures,
        long lostLeases
) {
}
