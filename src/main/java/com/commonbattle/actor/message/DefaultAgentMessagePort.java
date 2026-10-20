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
public final class DefaultAgentMessagePort implements AgentMessagePort, AgentMessagePortView, AutoCloseable {
    private final ActorSystem actors;
    private final RpcGateway rpc;
    private final AskTimeoutScheduler askTimeouts;
    private final RemoteCallFailureMapper remoteFailures;
    private final AgentMessagePortMetrics metrics = new AgentMessagePortMetrics();

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
        metrics.localTellAttempted();
        try {
            actors.send(target, task);
            metrics.localTellAccepted();
        } catch (RuntimeException e) {
            metrics.localTellRejected();
            throw e;
        }
    }

    @Override
    public AgentDeliveryResult tryTellLocal(ActorRef target, ActorTask task) {
        metrics.localTellAttempted();
        if (actors.trySend(target, task)) {
            metrics.localTellAccepted();
            return AgentDeliveryResult.acceptedResult();
        }
        metrics.localTellRejected();
        return rejectedLocal();
    }

    @Override
    public void tellLocal(ActorRef target, ActorTaskCategory category, ActorTask task) {
        tellLocal(target, ActorTask.categorized(category, task));
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
        metrics.localAskStarted();
        AtomicBoolean completed = new AtomicBoolean();
        ScheduledAskTimeout scheduledTimeout;
        try {
            scheduledTimeout = askTimeouts.schedule(timeout, () -> {
                if (completed.compareAndSet(false, true)) {
                    metrics.localAskTimedOut();
                    enqueueLocalAskCallback(requester, callbackCategory,
                            context -> callback.failure(context, new AskTimeoutException(target, timeout)), callback);
                }
            });
        } catch (RuntimeException error) {
            completed.set(true);
            metrics.localAskFailed();
            enqueueLocalAskCallback(requester, callbackCategory,
                    context -> callback.failure(context, error), callback);
            return;
        }
        boolean accepted = actors.trySend(target, ActorTask.categorized(requestCategory, targetContext -> {
            if (completed.get()) {
                return;
            }
            try {
                T response = ask.answer(targetContext);
                if (completed.compareAndSet(false, true)) {
                    scheduledTimeout.cancel();
                    metrics.localAskSucceeded();
                    enqueueLocalAskCallback(requester, callbackCategory,
                            context -> callback.success(context, response), callback);
                }
            } catch (Throwable error) {
                if (completed.compareAndSet(false, true)) {
                    scheduledTimeout.cancel();
                    metrics.localAskFailed();
                    enqueueLocalAskCallback(requester, callbackCategory,
                            context -> callback.failure(context, error), callback);
                }
            }
        }));
        if (!accepted && completed.compareAndSet(false, true)) {
            scheduledTimeout.cancel();
            metrics.localAskTargetDeliveryFailed();
            enqueueLocalAskCallback(requester, callbackCategory,
                    context -> callback.failure(context, new LocalAskDeliveryException(target)), callback);
        }
    }

    @Override
    public <T> void callRemote(
            ActorRef requester,
            RpcRequest<T> request,
            RemoteActorCallOptions options,
            RemoteActorCallback<T> callback
    ) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(callback, "callback");
        metrics.remoteCallStarted();
        AtomicBoolean completed = new AtomicBoolean();
        ScheduledAskTimeout scheduledTimeout;
        try {
            scheduledTimeout = askTimeouts.schedule(options.timeout(), () -> {
                if (completed.compareAndSet(false, true)) {
                    metrics.remoteTimedOut();
                    enqueueRemoteCallback(
                            requester,
                            options.callbackCategory(),
                            context -> callback.failure(
                                    context,
                                    AgentDeliveryResult.timeout("remote_actor_call_timeout"),
                                    new RemoteActorCallTimeoutException(request, options.timeout())
                            ),
                            callback
                    );
                }
            });
        } catch (RuntimeException error) {
            completed.set(true);
            metrics.remoteFailed();
            AgentDeliveryResult delivery = remoteFailures.map(error);
            enqueueRemoteCallback(
                    requester,
                    options.callbackCategory(),
                    context -> callback.failure(context, delivery, error),
                    callback
            );
            return;
        }
        try {
            rpc.call(request, new RpcCallback<>() {
                @Override
                public void success(T response) {
                    if (!completed.compareAndSet(false, true)) {
                        metrics.remoteLateResponse();
                        callback.lateResponse();
                        return;
                    }
                    scheduledTimeout.cancel();
                    metrics.remoteSucceeded();
                    enqueueRemoteCallback(
                            requester,
                            options.callbackCategory(),
                            context -> callback.success(context, response),
                            callback
                    );
                }

                @Override
                public void failure(Throwable error) {
                    if (!completed.compareAndSet(false, true)) {
                        metrics.remoteLateResponse();
                        callback.lateResponse();
                        return;
                    }
                    scheduledTimeout.cancel();
                    metrics.remoteFailed();
                    AgentDeliveryResult delivery = remoteFailures.map(error);
                    enqueueRemoteCallback(
                            requester,
                            options.callbackCategory(),
                            context -> callback.failure(context, delivery, error),
                            callback
                    );
                }
            });
        } catch (RuntimeException e) {
            if (!completed.compareAndSet(false, true)) {
                metrics.remoteLateResponse();
                callback.lateResponse();
                return;
            }
            scheduledTimeout.cancel();
            metrics.remoteFailed();
            AgentDeliveryResult delivery = remoteFailures.map(e);
            enqueueRemoteCallback(
                    requester,
                    options.callbackCategory(),
                    context -> callback.failure(context, delivery, e),
                    callback
            );
        }
    }

    @Override
    public void close() {
        askTimeouts.close();
    }

    @Override
    public AgentMessagePortStats agentMessagePortStats() {
        return metrics.snapshot();
    }

    private AgentDeliveryResult rejectedLocal() {
        return actors.isAccepting() ? AgentDeliveryResult.mailboxFull() : AgentDeliveryResult.systemClosed();
    }

    private <T> void enqueueRemoteCallback(
            ActorRef requester,
            ActorTaskCategory category,
            ActorTask task,
            RemoteActorCallback<T> callback
    ) {
        ActorTask trackedCallback = context -> {
            try {
                task.run(context);
            } finally {
                metrics.remoteCallSettled();
            }
        };
        AgentDeliveryResult delivery = actors.trySend(requester, ActorTask.categorized(category, trackedCallback))
                ? AgentDeliveryResult.acceptedResult()
                : rejectedLocal();
        if (!delivery.accepted()) {
            metrics.remoteCallbackDeliveryFailed();
            try {
                callback.callbackDeliveryFailed(delivery);
            } finally {
                metrics.remoteCallSettled();
            }
        }
    }

    private <T> void enqueueLocalAskCallback(
            ActorRef requester,
            ActorTaskCategory category,
            ActorTask task,
            LocalAskCallback<T> callback
    ) {
        if (!actors.trySend(requester, ActorTask.categorized(category, task))) {
            metrics.localAskCallbackDeliveryFailed();
            callback.callbackDeliveryFailed(rejectedLocal());
        }
    }
}
