package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认 Agent 通信端口实现。
 * 本服 tell 不经过序列化；远程 call 只依赖 RpcGateway，便于切换 Netty、内存传输或测试桩。
 */
public final class DefaultAgentMessagePort implements AgentMessagePort, AutoCloseable {
    private final ActorSystem actors;
    private final RpcGateway rpc;
    private final AskTimeoutScheduler askTimeouts;
    private final RemoteCallFailureMapper remoteFailures;

    public DefaultAgentMessagePort(ActorSystem actors, RpcGateway rpc) {
        this(actors, rpc, new ExecutorAskTimeoutScheduler());
    }

    public DefaultAgentMessagePort(ActorSystem actors, RpcGateway rpc, AskTimeoutScheduler askTimeouts) {
        this(actors, rpc, askTimeouts, RemoteCallFailureMapper.defaults());
    }

    public DefaultAgentMessagePort(
            ActorSystem actors,
            RpcGateway rpc,
            AskTimeoutScheduler askTimeouts,
            RemoteCallFailureMapper remoteFailures
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.askTimeouts = Objects.requireNonNull(askTimeouts, "askTimeouts");
        this.remoteFailures = Objects.requireNonNull(remoteFailures, "remoteFailures");
    }

    @Override
    public void tellLocal(ActorRef target, ActorTask task) {
        actors.send(target, task);
    }

    @Override
    public AgentDeliveryResult tryTellLocal(ActorRef target, ActorTask task) {
        return actors.trySend(target, task) ? AgentDeliveryResult.acceptedResult() : rejectedLocal();
    }

    @Override
    public void tellLocal(ActorRef target, ActorTaskCategory category, ActorTask task) {
        actors.send(target, ActorTask.categorized(category, task));
    }

    @Override
    public AgentDeliveryResult tryTellLocal(ActorRef target, ActorTaskCategory category, ActorTask task) {
        return tryTellLocal(target, ActorTask.categorized(category, task));
    }

    @Override
    public <T> void askLocal(
            ActorRef requester,
            ActorRef target,
            Duration timeout,
            LocalAsk<T> ask,
            LocalAskCallback<T> callback
    ) {
        askLocal(
                requester,
                target,
                timeout,
                ActorTaskCategory.SYSTEM,
                ActorTaskCategory.RPC_CALLBACK,
                ask,
                callback
        );
    }

    @Override
    public <T> void askLocal(
            ActorRef requester,
            ActorRef target,
            Duration timeout,
            ActorTaskCategory requestCategory,
            ActorTaskCategory callbackCategory,
            LocalAsk<T> ask,
            LocalAskCallback<T> callback
    ) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(requestCategory, "requestCategory");
        Objects.requireNonNull(callbackCategory, "callbackCategory");
        Objects.requireNonNull(ask, "ask");
        Objects.requireNonNull(callback, "callback");
        AtomicBoolean completed = new AtomicBoolean();
        ScheduledAskTimeout scheduledTimeout = askTimeouts.schedule(timeout, () -> {
            if (completed.compareAndSet(false, true)) {
                actors.trySend(requester, ActorTask.categorized(callbackCategory,
                        context -> callback.failure(context, new AskTimeoutException(target, timeout))));
            }
        });
        boolean accepted = actors.trySend(target, ActorTask.categorized(requestCategory, targetContext -> {
            if (completed.get()) {
                return;
            }
            try {
                T response = ask.answer(targetContext);
                if (completed.compareAndSet(false, true)) {
                    scheduledTimeout.cancel();
                    actors.trySend(requester, ActorTask.categorized(callbackCategory,
                            context -> callback.success(context, response)));
                }
            } catch (Throwable error) {
                if (completed.compareAndSet(false, true)) {
                    scheduledTimeout.cancel();
                    actors.trySend(requester, ActorTask.categorized(callbackCategory,
                            context -> callback.failure(context, error)));
                }
            }
        }));
        if (!accepted && completed.compareAndSet(false, true)) {
            scheduledTimeout.cancel();
            actors.trySend(requester, ActorTask.categorized(callbackCategory,
                    context -> callback.failure(context, new LocalAskDeliveryException(target))));
        }
    }

    @Override
    public <T> void callRemote(RpcRequest<T> request, RpcCallback<T> callback) {
        rpc.call(request, callback);
    }

    @Override
    public <T> void callRemote(RpcRequest<T> request, RemoteAgentCallback<T> callback) {
        Objects.requireNonNull(callback, "callback");
        rpc.call(request, new RpcCallback<>() {
            @Override
            public void success(T response) {
                callback.success(response);
            }

            @Override
            public void failure(Throwable error) {
                callback.failure(remoteFailures.map(error), error);
            }
        });
    }

    @Override
    public void close() {
        askTimeouts.close();
    }

    private AgentDeliveryResult rejectedLocal() {
        return actors.isAccepting() ? AgentDeliveryResult.mailboxFull() : AgentDeliveryResult.systemClosed();
    }
}
