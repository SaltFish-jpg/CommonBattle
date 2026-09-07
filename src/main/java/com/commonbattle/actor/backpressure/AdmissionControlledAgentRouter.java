package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentRoute;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.message.AgentDeliveryResult;

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
        AdmissionRouteResult routed = route(target, operation);
        if (routed.admission().accepted()) {
            router.tellResolvedLocal(routed.route(), task);
        }
        return routed;
    }

    public AdmissionRouteResult route(AgentIdentity target, String operation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        AdmissionDecision decision = admissions.admit(target, operation);
        if (!decision.accepted()) {
            return new AdmissionRouteResult(decision, AgentRoute.missing());
        }
        AgentRoute route = router.resolve(target);
        if (route.type() == AgentRouteType.MISSING) {
            return new AdmissionRouteResult(AdmissionDecision.reject(route.reason(), java.time.Duration.ZERO), route);
        }
        return new AdmissionRouteResult(decision, route);
    }

    public AgentDeliveryResult deliverLocal(AdmissionRouteResult routed, ActorTask task) {
        Objects.requireNonNull(routed, "routed");
        Objects.requireNonNull(task, "task");
        if (routed.admission().accepted()) {
            return router.tellResolvedLocal(routed.route(), task);
        }
        return AgentDeliveryResult.rejected(routed.admission().reason(), routed.admission().retryAfter());
    }
}
