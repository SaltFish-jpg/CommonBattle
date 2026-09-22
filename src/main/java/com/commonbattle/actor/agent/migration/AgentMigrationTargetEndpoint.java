package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleResultCallback;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;

/**
 * 目标服务 Agent 迁入 RPC 端点。
 * 端点校验目录归属后把恢复动作投递到目标 Actor 邮箱，只有恢复动作执行成功后才向源服确认接收。
 */
public final class AgentMigrationTargetEndpoint {
    private final AgentLifecycleManager lifecycles;
    private final AgentMigrationRestoreHandler restoreHandler;
    private final AgentMigrationTargetReceiptStore receipts;

    public AgentMigrationTargetEndpoint(AgentLifecycleManager lifecycles, AgentMigrationRestoreHandler restoreHandler) {
        this(lifecycles, restoreHandler, new InMemoryAgentMigrationTargetReceiptStore());
    }

    public AgentMigrationTargetEndpoint(
            AgentLifecycleManager lifecycles,
            AgentMigrationRestoreHandler restoreHandler,
            AgentMigrationTargetReceiptStore receipts
    ) {
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        this.restoreHandler = Objects.requireNonNull(restoreHandler, "restoreHandler");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(AgentMigrationOperations.ACCEPT, (request, responder) -> {
            AgentMigrationAcceptRequest payload = (AgentMigrationAcceptRequest) request.payload();
            AgentMigrationAcceptResponse cached = receipt(payload);
            if (cached != null) {
                responder.success(cached);
                return;
            }
            try {
                lifecycles.acceptMigrated(
                        payload.identity(),
                        payload.actorId(),
                        context -> restoreHandler.restore(payload, context),
                        new AgentLifecycleResultCallback() {
                            @Override
                            public void succeeded(AgentLocation location) {
                                respond(payload, responder, AgentMigrationAcceptResponse.success());
                            }

                            @Override
                            public void failed(Throwable error) {
                                respond(payload, responder,
                                        AgentMigrationAcceptResponse.rejected(messageOf(error)));
                            }
                        }
                );
            } catch (RuntimeException e) {
                responder.success(AgentMigrationAcceptResponse.rejected(messageOf(e)));
            }
        });
    }

    private AgentMigrationAcceptResponse receipt(AgentMigrationAcceptRequest request) {
        if (request.taskId().isBlank()) {
            return null;
        }
        return receipts.find(request.taskId()).orElse(null);
    }

    private void respond(
            AgentMigrationAcceptRequest request,
            com.commonbattle.cluster.rpc.RpcResponder responder,
            AgentMigrationAcceptResponse response
    ) {
        receipts.save(request.taskId(), response);
        responder.success(response);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getName() : e.getMessage();
    }
}
