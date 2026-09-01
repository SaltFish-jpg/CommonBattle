package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;

import java.time.Duration;

/**
 * Agent 之间通信的统一端口。
 * 本服消息直接投递 Actor 邮箱；跨服请求通过 RPC 网关发出，二者在调用侧统一，但底层模型保持分离。
 */
public interface AgentMessagePort {
    void tellLocal(ActorRef target, ActorTask task);

    <T> void askLocal(
            ActorRef requester,
            ActorRef target,
            Duration timeout,
            LocalAsk<T> ask,
            LocalAskCallback<T> callback
    );

    <T> void callRemote(RpcRequest<T> request, RpcCallback<T> callback);
}
