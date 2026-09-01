package com.commonbattle.actor.rpc;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;

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

    public ActorRpcClient(ActorSystem system, ActorRef owner, RpcGateway gateway) {
        this.system = Objects.requireNonNull(system, "system");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public <T> void call(
            RpcRequest<T> request,
            BiConsumer<ActorContext, T> onSuccess,
            BiConsumer<ActorContext, Throwable> onFailure
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");
        gateway.call(request, new RpcCallback<>() {
            @Override
            public void success(T response) {
                system.send(owner, context -> onSuccess.accept(context, response));
            }

            @Override
            public void failure(Throwable error) {
                system.send(owner, context -> onFailure.accept(context, error));
            }
        });
    }
}
