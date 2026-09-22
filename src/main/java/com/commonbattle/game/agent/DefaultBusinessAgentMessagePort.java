package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.AgentRoute;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.backpressure.InboundAdmissionController;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.actor.message.AskTimeoutScheduler;
import com.commonbattle.actor.message.ExecutorAskTimeoutScheduler;
import com.commonbattle.actor.message.LocalAsk;
import com.commonbattle.actor.message.LocalAskCallback;
import com.commonbattle.actor.message.RemoteAgentCallback;
import com.commonbattle.actor.message.ScheduledAskTimeout;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.player.PlayerBusinessCommandGateway;
import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.session.PlayerCommand;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认游戏业务 Agent 通信端口。
 * 本类只做门面聚合：本服 Agent 通信交给 AgentMessagePort，玩家业务命令交给 PlayerBusinessCommandGateway。
 */
public final class DefaultBusinessAgentMessagePort implements BusinessAgentMessagePort, BusinessAgentMessageView, AutoCloseable {
    private final AgentMessagePort messages;
    private final PlayerBusinessCommandGateway playerCommands;
    private final LifecycleAwareAgentRouter router;
    private final BusinessAgentHandlerRegistry handlers;
    private final InboundAdmissionController admissions;
    private final AskTimeoutScheduler remoteTimeouts;
    private final BusinessAgentMessageMetrics metrics = new BusinessAgentMessageMetrics();

    public DefaultBusinessAgentMessagePort(AgentMessagePort messages, PlayerBusinessCommandGateway playerCommands) {
        this(messages, playerCommands, null, null);
    }

    public DefaultBusinessAgentMessagePort(
            AgentMessagePort messages,
            PlayerBusinessCommandGateway playerCommands,
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers
    ) {
        this(messages, playerCommands, router, handlers, (target, operation) -> AdmissionDecision.accept());
    }

    public DefaultBusinessAgentMessagePort(
            AgentMessagePort messages,
            PlayerBusinessCommandGateway playerCommands,
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers,
            InboundAdmissionController admissions
    ) {
        this(messages, playerCommands, router, handlers, admissions, new ExecutorAskTimeoutScheduler());
    }

    public DefaultBusinessAgentMessagePort(
            AgentMessagePort messages,
            PlayerBusinessCommandGateway playerCommands,
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers,
            InboundAdmissionController admissions,
            AskTimeoutScheduler remoteTimeouts
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.playerCommands = Objects.requireNonNull(playerCommands, "playerCommands");
        this.router = router;
        this.handlers = handlers;
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.remoteTimeouts = Objects.requireNonNull(remoteTimeouts, "remoteTimeouts");
    }

    @Override
    public void tellLocal(ActorRef target, ActorTask task) {
        messages.tellLocal(target, task);
    }

    @Override
    public AgentDeliveryResult tryTellLocal(ActorRef target, ActorTask task) {
        return messages.tryTellLocal(target, task);
    }

    @Override
    public void tellLocal(ActorRef target, ActorTaskCategory category, ActorTask task) {
        messages.tellLocal(target, category, task);
    }

    @Override
    public AgentDeliveryResult tryTellLocal(ActorRef target, ActorTaskCategory category, ActorTask task) {
        return messages.tryTellLocal(target, category, task);
    }

    @Override
    public <T> void askLocal(
            ActorRef requester,
            ActorRef target,
            Duration timeout,
            LocalAsk<T> ask,
            LocalAskCallback<T> callback
    ) {
        messages.askLocal(requester, target, timeout, ask, callback);
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
        messages.askLocal(requester, target, timeout, requestCategory, callbackCategory, ask, callback);
    }

    @Override
    public <T> void callRemote(RpcRequest<T> request, RpcCallback<T> callback) {
        messages.callRemote(request, callback);
    }

    @Override
    public <T> void callRemote(RpcRequest<T> request, RemoteAgentCallback<T> callback) {
        messages.callRemote(request, callback);
    }

    @Override
    public <T> void requestAgent(
            ActorRef requester,
            AgentIdentity target,
            String operation,
            Object payload,
            Class<T> responseType,
            BusinessAgentCallOptions options,
            LocalAskCallback<T> callback
    ) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(callback, "callback");
        ensureAgentRequestConfigured();
        metrics.submittedRequest();
        BusinessAgentRequest request = new BusinessAgentRequest(target, operation, payload, options.idempotencyKey());
        AdmissionDecision admission = admissions.admit(target, operation);
        if (!admission.accepted()) {
            metrics.rejectedRequest();
            failOnRequesterMailbox(requester, options, callback,
                    new BusinessAgentRequestException(target, operation, admission.reason()));
            return;
        }
        AgentRoute route = router.resolve(target);
        if (route.type() == AgentRouteType.LOCAL) {
            metrics.localRequest();
            askLocalAgent(requester, route.location().orElseThrow(), request, responseType, options, callback);
            return;
        }
        if (route.type() == AgentRouteType.REMOTE) {
            metrics.remoteRequest();
            callRemoteAgent(requester, route.location().orElseThrow(), request, responseType, options, callback);
            return;
        }
        metrics.rejectedRequest();
        failOnRequesterMailbox(requester, options, callback,
                new BusinessAgentRequestException(target, operation, route.reason()));
    }

    @Override
    public void sendPlayerCommand(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback) {
        playerCommands.submit(command, callback);
    }

    @Override
    public void sendLocalPlayerCommand(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback) {
        playerCommands.submitLocalOnly(command, callback);
    }

    public BusinessAgentMessageStats stats() {
        return metrics.snapshot();
    }

    private <T> void askLocalAgent(
            ActorRef requester,
            AgentLocation location,
            BusinessAgentRequest request,
            Class<T> responseType,
            BusinessAgentCallOptions options,
            LocalAskCallback<T> callback
    ) {
        messages.askLocal(
                requester,
                location.actorRef(),
                options.timeout(),
                options.requestCategory(),
                options.callbackCategory(),
                context -> responseType.cast(handlers.dispatch(context, request)),
                callback
        );
    }

    private <T> void callRemoteAgent(
            ActorRef requester,
            AgentLocation location,
            BusinessAgentRequest request,
            Class<T> responseType,
            BusinessAgentCallOptions options,
            LocalAskCallback<T> callback
    ) {
        AtomicBoolean completed = new AtomicBoolean();
        ScheduledAskTimeout scheduledTimeout = remoteTimeouts.schedule(options.timeout(), () -> {
            if (completed.compareAndSet(false, true)) {
                metrics.remoteTimedOutResponse();
                failOnRequesterMailbox(
                        requester,
                        options,
                        callback,
                        new BusinessAgentRequestTimeoutException(request.target(), request.operation(), options.timeout())
                );
            }
        });
        messages.callRemote(
                RpcRequest.toService(location.serviceId(), BusinessAgentRpcOperations.DISPATCH, request, responseType),
            new RpcCallback<>() {
                    @Override
                    public void success(T response) {
                        if (!completed.compareAndSet(false, true)) {
                            metrics.lateRemoteResponse();
                            return;
                        }
                        scheduledTimeout.cancel();
                        metrics.remoteSuccessResponse();
                        recordCallbackDelivery(messages.tryTellLocal(requester, options.callbackCategory(),
                                context -> callback.success(context, response)));
                    }

                    @Override
                    public void failure(Throwable error) {
                        if (!completed.compareAndSet(false, true)) {
                            metrics.lateRemoteResponse();
                            return;
                        }
                        scheduledTimeout.cancel();
                        metrics.remoteFailureResponse();
                        recordCallbackDelivery(messages.tryTellLocal(requester, options.callbackCategory(),
                                context -> callback.failure(context, error)));
                    }
                }
        );
    }

    private <T> void failOnRequesterMailbox(
            ActorRef requester,
            BusinessAgentCallOptions options,
            LocalAskCallback<T> callback,
            Throwable error
    ) {
        recordCallbackDelivery(messages.tryTellLocal(
                requester,
                options.callbackCategory(),
                context -> callback.failure(context, error)
        ));
    }

    private void ensureAgentRequestConfigured() {
        if (router == null || handlers == null) {
            throw new IllegalStateException("Business agent request routing is not configured");
        }
    }

    @Override
    public void close() {
        remoteTimeouts.close();
    }

    private void recordCallbackDelivery(AgentDeliveryResult delivery) {
        if (!delivery.accepted()) {
            metrics.callbackDeliveryFailed(delivery.status());
        }
    }
}
