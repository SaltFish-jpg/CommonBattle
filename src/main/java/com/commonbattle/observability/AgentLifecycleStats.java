package com.commonbattle.observability;

import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;

import java.util.Map;

/**
 * 本服务 Agent 生命周期状态计数。
 */
public record AgentLifecycleStats(Map<AgentLifecycleState, Integer> counts) {
    public AgentLifecycleStats {
        counts = Map.copyOf(counts);
    }

    public int count(AgentLifecycleState state) {
        return counts.getOrDefault(state, 0);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
