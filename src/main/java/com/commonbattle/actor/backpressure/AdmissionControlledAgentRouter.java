package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentRoute;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;

import java.util.Objects;

/**
 * 带入站准入控制的 Agent 路由器。
 * 准入失败时不会触碰 Actor 邮箱，调用方可直接向客户端或上游 RPC 返回限流错误。
 */
public final class AdmissionControlledAgentRouter {
    private final InboundAdmissionController admissions;
    private final LifecycleAwareAgentRouter router;

    public AdmissionControlledAgentRouter(InboundAdmissionController admissions, LifecycleAwareAgentRouter router) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.router = Objects.requireNonNull(router, "router");
    }

    public AdmissionRouteResult tellLocalOrRoute(AgentIdentity target, String operation, ActorTask task) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(task, "task");
        AdmissionDecision decision = admissions.admit(target, operation);
        if (!decision.accepted()) {
            return new AdmissionRouteResult(decision, AgentRoute.missing());
        }
        AgentRoute route = router.tellLocalOrRoute(target, task);
        if (route.type() == AgentRouteType.MISSING) {
            return new AdmissionRouteResult(AdmissionDecision.reject("agent_missing", java.time.Duration.ZERO), route);
        }
        return new AdmissionRouteResult(decision, route);
    }
}
