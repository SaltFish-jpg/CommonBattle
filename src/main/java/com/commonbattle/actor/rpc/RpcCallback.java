package com.commonbattle.actor.rpc;

/**
 * RPC 网关完成异步调用后的回调。
 * 实现方只应传递结果，不应在网络线程里直接执行玩家或场景业务逻辑。
 */
public interface RpcCallback<T> {
    void success(T response);

    void failure(Throwable error);
}
