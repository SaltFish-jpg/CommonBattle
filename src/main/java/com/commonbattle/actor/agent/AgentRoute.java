package com.commonbattle.actor.agent;

import java.util.Optional;

/**
 * Agent 路由结果。
 * LOCAL 可直接投递本地邮箱，REMOTE 交给跨服 RPC，MISSING 表示目录暂未发现 owner。
 */
public record AgentRoute(AgentRouteType type, Optional<AgentLocation> location, String reason) {
    public static AgentRoute local(AgentLocation location) {
        return new AgentRoute(AgentRouteType.LOCAL, Optional.of(location), "");
    }

    public static AgentRoute remote(AgentLocation location) {
        return new AgentRoute(AgentRouteType.REMOTE, Optional.of(location), "");
    }

    public static AgentRoute missing() {
        return missing("agent_missing");
    }

    public static AgentRoute missing(String reason) {
        return new AgentRoute(AgentRouteType.MISSING, Optional.empty(), reason);
    }
}
