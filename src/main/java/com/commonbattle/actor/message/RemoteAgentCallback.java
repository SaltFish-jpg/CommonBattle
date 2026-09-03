package com.commonbattle.actor.message;

/**
 * 远程 Agent 调用回调。
 * 成功路径返回业务响应；失败路径返回统一投递结果，便于调用方把 RPC 超时、熔断和远端不可达映射到治理策略。
 */
public interface RemoteAgentCallback<T> {
    void success(T response);

    void failure(AgentDeliveryResult delivery, Throwable error);
}
