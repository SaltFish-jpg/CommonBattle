package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentRoute;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.message.AgentMessagePort;

import java.util.Objects;

/**
 * 生命周期感知的 Agent 路由器。
 * 只有 ACTIVE 的本地 owner 会收到本地邮箱消息，PASSIVATING/MIGRATING 的旧 owner 不再接新业务。
 */
public final class LifecycleAwareAgentRouter {
    private final AgentLifecycleManager lifecycles;
    private final AgentMessagePort messages;

    public LifecycleAwareAgentRouter(AgentLifecycleManager lifecycles, AgentMessagePort messages) {
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public AgentRoute resolve(AgentIdentity identity) {
        return lifecycles.resolve(identity);
    }

    public AgentRoute tellLocalOrRoute(AgentIdentity identity, ActorTask task) {
        Objects.requireNonNull(task, "task");
        AgentRoute route = resolve(identity);
        tellResolvedLocal(route, task);
        return route;
    }

    public void tellResolvedLocal(AgentRoute route, ActorTask task) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(task, "task");
        if (route.type() == AgentRouteType.LOCAL) {
            messages.tellLocal(route.location().orElseThrow().actorRef(), task);
        }
    }
}
