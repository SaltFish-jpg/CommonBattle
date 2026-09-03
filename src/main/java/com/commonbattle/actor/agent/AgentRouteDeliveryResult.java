package com.commonbattle.actor.agent;

import com.commonbattle.actor.message.AgentDeliveryResult;

import java.util.Objects;

/**
 * Agent 路由与本地投递结果。
 * LOCAL 路由会携带邮箱投递结果；REMOTE 路由只表示已解析到远端 owner，由调用方继续发 RPC。
 */
public record AgentRouteDeliveryResult(AgentRoute route, AgentDeliveryResult delivery) {
    public AgentRouteDeliveryResult {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(delivery, "delivery");
    }
}
