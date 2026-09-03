package com.commonbattle.actor.rpc;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.RemoteCallFailureMapper;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 绑定到单个 Actor 的 RPC 客户端。
 * RPC 回调线程只把成功或失败结果封装成邮箱消息，真正的状态修改回到 Actor 串行上下文中执行。
 */
public final class ActorRpcClient {
    private final ActorSystem system;
    private final ActorRef owner;
    private final RpcGateway gateway;
    private final RemoteCallFailureMapper failures;
    private final ActorRpcClientMetrics metrics = new ActorRpcClientMetrics();

    public ActorRpcClient(ActorSystem system, ActorRef owner, RpcGateway gateway) {
        this(system, owner, gateway, RemoteCallFailureMapper.defaults());
    }

    public ActorRpcClient(
            ActorSystem system,
            ActorRef owner,
            RpcGateway gateway,
            RemoteCallFailureMapper failures
    ) {
        this.system = Objects.requireNonNull(system, "system");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    public <T> void call(
            RpcRequest<T> request,
            BiConsumer<ActorContext, T> onSuccess,
            BiConsumer<ActorContext, Throwable> onFailure
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");
        call(request, new ActorRpcHandler<>() {
            @Override
            public void success(ActorContext context, T response) {
                onSuccess.accept(context, response);
            }

            @Override
            public void failure(ActorContext context, com.commonbattle.actor.message.AgentDeliveryResult delivery, Throwable error) {
                onFailure.accept(context, error);
            }
        });
    }

    public <T> void call(RpcRequest<T> request, ActorRpcHandler<T> handler) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        metrics.callSubmitted();
        try {
            gateway.call(request, new RpcCallback<>() {
                @Override
                public void success(T response) {
                    metrics.succeededResponse();
                    enqueueCallback(context -> handler.success(context, response));
                }

                @Override
                public void failure(Throwable error) {
                    AgentDeliveryResult delivery = failures.map(error);
                    metrics.failedResponse(delivery.status());
                    enqueueCallback(context -> handler.failure(context, delivery, error));
                }
            });
        } catch (RuntimeException e) {
            AgentDeliveryResult delivery = failures.map(e);
            metrics.failedResponse(delivery.status());
            enqueueCallback(context -> handler.failure(context, delivery, e));
        }
    }

    public ActorRpcClientStats stats() {
        return metrics.snapshot();
    }

    private void enqueueCallback(ActorTask task) {
        boolean accepted = system.trySend(owner, ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK, task));
        if (!accepted) {
            metrics.callbackDeliveryFailed();
        }
    }
}
