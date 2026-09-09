package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用业务 Agent 操作注册表。
 * 每个服务只注册本进程能承载的业务操作，跨服入口会先路由到 owner，再在 owner 邮箱内分派到这里。
 */
public final class BusinessAgentHandlerRegistry {
    private final Map<String, BusinessAgentHandler> handlers = new ConcurrentHashMap<>();

    public void handle(String operation, BusinessAgentHandler handler) {
        handlers.put(Objects.requireNonNull(operation, "operation"), Objects.requireNonNull(handler, "handler"));
    }

    public Object dispatch(ActorContext context, BusinessAgentRequest request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        BusinessAgentHandler handler = handlers.get(request.operation());
        if (handler == null) {
            throw new BusinessAgentRequestException(request.target(), request.operation(), "unknown_operation");
        }
        return handler.handle(context, request);
    }
}
