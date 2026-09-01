package com.commonbattle.actor.agent;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.cluster.ServiceId;

import java.util.Objects;

/**
 * 业务 Agent 的当前位置。
 * serviceId 表示归属进程，actorRef 表示该进程内的邮箱身份。
 */
public record AgentLocation(ServiceId serviceId, ActorRef actorRef) {
    public AgentLocation {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(actorRef, "actorRef");
    }

    public boolean isLocal(ServiceId localServiceId) {
        return serviceId.equals(localServiceId);
    }
}
