package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;

import java.time.Duration;

/**
 * Agent 之间通信的统一端口。
 * 本服消息直接投递 Actor 邮箱；跨服请求通过 RPC 网关发出，二者在调用侧统一，但底层模型保持分离。
 */
public interface AgentMessagePort {
    void tellLocal(ActorRef target, ActorTask task);

    default AgentDeliveryResult tryTellLocal(ActorRef target, ActorTask task) {
        try {
            tellLocal(target, task);
            return AgentDeliveryResult.acceptedResult();
        } catch (com.commonbattle.actor.ActorSystemClosedException e) {
            return AgentDeliveryResult.systemClosed();
        } catch (com.commonbattle.actor.MailboxFullException e) {
            return AgentDeliveryResult.mailboxFull();
        }
    }

    default void tellLocal(ActorRef target, ActorTaskCategory category, ActorTask task) {
        tellLocal(target, ActorTask.categorized(category, task));
    }

    default AgentDeliveryResult tryTellLocal(ActorRef target, ActorTaskCategory category, ActorTask task) {
        return tryTellLocal(target, ActorTask.categorized(category, task));
    }

    <T> void askLocal(
            ActorRef requester,
            ActorRef target,
            Duration timeout,
            LocalAsk<T> ask,
            LocalAskCallback<T> callback
    );

    default <T> void askLocal(
            ActorRef requester,
            ActorRef target,
            Duration timeout,
            ActorTaskCategory requestCategory,
            ActorTaskCategory callbackCategory,
            LocalAsk<T> ask,
            LocalAskCallback<T> callback
    ) {
        askLocal(requester, target, timeout, ask, callback);
    }

    <T> void callRemote(RpcRequest<T> request, RpcCallback<T> callback);

    default <T> void callRemote(RpcRequest<T> request, RemoteAgentCallback<T> callback) {
        callRemote(request, new RpcCallback<>() {
            @Override
            public void success(T response) {
                callback.success(response);
            }

            @Override
            public void failure(Throwable error) {
                callback.failure(RemoteCallFailureMapper.defaults().map(error), error);
            }
        });
    }
}
