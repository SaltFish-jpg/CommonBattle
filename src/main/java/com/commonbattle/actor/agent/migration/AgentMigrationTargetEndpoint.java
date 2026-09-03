package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;

/**
 * 目标服务 Agent 迁入 RPC 端点。
 * 端点只负责校验目录归属并把恢复动作投递到目标 Actor 邮箱，具体状态恢复由业务 handler 完成。
 */
public final class AgentMigrationTargetEndpoint {
    private final AgentLifecycleManager lifecycles;
    private final AgentMigrationRestoreHandler restoreHandler;

    public AgentMigrationTargetEndpoint(AgentLifecycleManager lifecycles, AgentMigrationRestoreHandler restoreHandler) {
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        this.restoreHandler = Objects.requireNonNull(restoreHandler, "restoreHandler");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(AgentMigrationOperations.ACCEPT, (request, responder) -> {
            AgentMigrationAcceptRequest payload = (AgentMigrationAcceptRequest) request.payload();
            try {
                lifecycles.acceptMigrated(
                        payload.identity(),
                        payload.actorId(),
                        context -> restoreHandler.restore(payload, context)
                );
                responder.success(AgentMigrationAcceptResponse.success());
            } catch (RuntimeException e) {
                responder.success(AgentMigrationAcceptResponse.rejected(messageOf(e)));
            }
        });
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getName() : e.getMessage();
    }
}
