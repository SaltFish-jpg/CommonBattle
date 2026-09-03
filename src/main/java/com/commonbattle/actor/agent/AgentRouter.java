package com.commonbattle.actor.agent;

import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.cluster.ServiceId;

import java.util.Objects;

/**
 * Agent 消息路由器。
 * 本服 owner 直接投递邮箱；远程 owner 只返回位置，由调用方选择 RPC operation 和业务 payload。
 */
public final class AgentRouter {
    private final ServiceId localServiceId;
    private final AgentDirectory directory;
    private final AgentMessagePort messages;

    public AgentRouter(ServiceId localServiceId, AgentDirectory directory, AgentMessagePort messages) {
        this.localServiceId = Objects.requireNonNull(localServiceId, "localServiceId");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public AgentRoute resolve(AgentIdentity identity) {
        return directory.locate(identity)
                .map(location -> location.isLocal(localServiceId)
                        ? AgentRoute.local(location)
                        : AgentRoute.remote(location))
                .orElseGet(AgentRoute::missing);
    }

    public AgentRoute tellLocalOrRoute(AgentIdentity identity, ActorTask task) {
        return deliverLocalOrRoute(identity, task).route();
    }

    public AgentRouteDeliveryResult deliverLocalOrRoute(AgentIdentity identity, ActorTask task) {
        Objects.requireNonNull(task, "task");
        AgentRoute route = resolve(identity);
        if (route.type() == AgentRouteType.LOCAL) {
            return new AgentRouteDeliveryResult(
                    route,
                    messages.tryTellLocal(route.location().orElseThrow().actorRef(), task)
            );
        }
        if (route.type() == AgentRouteType.REMOTE) {
            return new AgentRouteDeliveryResult(route, AgentDeliveryResult.routedRemote());
        }
        return new AgentRouteDeliveryResult(route, AgentDeliveryResult.routeMissing());
    }
}
