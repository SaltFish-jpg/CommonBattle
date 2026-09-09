package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.agent.AgentRoute;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;

/**
 * 通用业务 Agent RPC 端点。
 * 网络入口只做路由和投递，真正的业务处理必须回到目标 owner Actor 邮箱内执行。
 */
public final class BusinessAgentRpcEndpoint {
    private final LifecycleAwareAgentRouter router;
    private final BusinessAgentHandlerRegistry handlers;

    public BusinessAgentRpcEndpoint(LifecycleAwareAgentRouter router, BusinessAgentHandlerRegistry handlers) {
        this.router = Objects.requireNonNull(router, "router");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(BusinessAgentRpcOperations.DISPATCH, (envelope, responder) -> {
            BusinessAgentRequest request = (BusinessAgentRequest) envelope.payload();
            AgentRoute route = router.resolve(request.target());
            if (route.type() != AgentRouteType.LOCAL) {
                responder.failure(new BusinessAgentRequestException(request.target(), request.operation(), route.reason()));
                return;
            }
            AgentDeliveryResult delivery = router.tellResolvedLocal(route, ActorTask.categorized(
                    com.commonbattle.actor.ActorTaskCategory.DEFAULT,
                    context -> {
                        try {
                            responder.success(handlers.dispatch(context, request));
                        } catch (RuntimeException | Error e) {
                            responder.failure(e);
                        }
                    }
            ));
            if (!delivery.accepted()) {
                responder.failure(new BusinessAgentRequestException(
                        request.target(),
                        request.operation(),
                        delivery.reason()
                ));
            }
        });
    }
}
