package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 迁移源服务使用的目标端接收客户端。
 * 它通过精确 ServiceId 定点调用目标 Game/Scene，避免迁移请求被普通同类服务负载均衡到错误节点。
 */
public final class RemoteAgentMigrationClient {
    private final ClusterRpcGateway gateway;

    public RemoteAgentMigrationClient(ClusterRpcGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public AgentMigrationAcceptResponse accept(ServiceId targetServiceId, AgentMigrationAcceptRequest request) {
        return await(RpcRequest.toService(
                targetServiceId,
                AgentMigrationOperations.ACCEPT,
                request,
                AgentMigrationAcceptResponse.class
        ));
    }

    private <T> T await(RpcRequest<T> request) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        gateway.call(request, new RpcCallback<>() {
            @Override
            public void success(T response) {
                value.set(response);
                done.countDown();
            }

            @Override
            public void failure(Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });
        try {
            if (!done.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Agent migration RPC timeout: " + request.operation());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting agent migration RPC", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Agent migration RPC failed: " + request.operation(), failure.get());
        }
        return value.get();
    }
}
