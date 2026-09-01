package com.commonbattle.actor.rpc;

/**
 * Actor 层依赖的异步 RPC 出口。
 * 网络实现负责发包和收包；收到回包后调用 callback，业务恢复由 Actor 邮箱重新调度。
 */
public interface RpcGateway {
    <T> void call(RpcRequest<T> request, RpcCallback<T> callback);
}
