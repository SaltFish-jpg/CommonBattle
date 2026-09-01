package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.agent.AgentRoute;

/**
 * 带准入结果的 Agent 路由结果。
 */
public record AdmissionRouteResult(AdmissionDecision admission, AgentRoute route) {
}
