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
import com.commonbattle.actor.message.LocalAsk;
import com.commonbattle.actor.message.LocalAskCallback;
import com.commonbattle.actor.message.RemoteActorCallback;
import com.commonbattle.actor.message.RemoteActorCallOptions;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.player.PlayerBusinessCommandGateway;
import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.session.PlayerCommand;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

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
    private final BusinessAgentLocalIdempotencyCache localIdempotency;
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
        this(messages, playerCommands, router, handlers, admissions,
                BusinessAgentIdempotencyConfig.defaults(), Clock.systemUTC());
    }

    public DefaultBusinessAgentMessagePort(
            AgentMessagePort messages,
            PlayerBusinessCommandGateway playerCommands,
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers,
            InboundAdmissionController admissions,
            BusinessAgentIdempotencyConfig idempotencyConfig,
            Clock clock
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.playerCommands = Objects.requireNonNull(playerCommands, "playerCommands");
        this.router = router;
        this.handlers = handlers;
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.localIdempotency = new BusinessAgentLocalIdempotencyCache(idempotencyConfig, clock);
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
    public <T> void callRemote(
            ActorRef requester,
            RpcRequest<T> request,
            RemoteActorCallOptions options,
            RemoteActorCallback<T> callback
    ) {
        messages.callRemote(requester, request, options, callback);
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
    public <T> void requestPlayerCommand(
            ActorRef requester,
            PlayerCommand command,
            Class<T> payloadType,
            BusinessAgentCallOptions options,
            LocalAskCallback<T> callback
    ) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(callback, "callback");
        playerCommands.submitDetailed(command, response -> recordCallbackDelivery(messages.tryTellLocal(
                requester,
                options.callbackCategory(),
                context -> completePlayerCommandRequest(context, command, response, payloadType, callback)
        )));
    }

    public BusinessAgentMessageStats stats() {
        return metrics.snapshot().withLocalIdempotency(localIdempotency.stats());
    }

    private <T> void askLocalAgent(
            ActorRef requester,
            AgentLocation location,
            BusinessAgentRequest request,
            Class<T> responseType,
            BusinessAgentCallOptions options,
            LocalAskCallback<T> callback
    ) {
        if (request.idempotencyKey().isBlank() || !localIdempotency.enabled()) {
            askLocalAgentOnce(requester, location, request, responseType, options, callback);
            return;
        }
        BusinessAgentLocalIdempotencyCache.Key key = new BusinessAgentLocalIdempotencyCache.Key(
                requester.id(),
                request.target(),
                request.operation(),
                request.idempotencyKey()
        );
        BusinessAgentLocalIdempotencyCache.Waiter waiter = (context, result) ->
                completeLocalAgentRequest(context, result, responseType, callback);
        BusinessAgentLocalIdempotencyCache.Begin begin = localIdempotency.begin(key, waiter);
        if (begin.state() == BusinessAgentLocalIdempotencyCache.State.JOINED) {
            return;
        }
        if (begin.state() == BusinessAgentLocalIdempotencyCache.State.REPLAY) {
            recordCallbackDelivery(messages.tryTellLocal(
                    requester,
                    options.callbackCategory(),
                    context -> waiter.complete(context, begin.result())
            ));
            return;
        }
        messages.askLocal(
                requester,
                location.actorRef(),
                options.timeout(),
                options.requestCategory(),
                options.callbackCategory(),
                context -> handlers.dispatch(context, request),
                new LocalAskCallback<>() {
                    @Override
                    public void success(com.commonbattle.actor.ActorContext context, Object response) {
                        completeLocalIdempotentRequest(
                                context,
                                key,
                                BusinessAgentLocalIdempotencyCache.Result.success(response)
                        );
                    }

                    @Override
                    public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                        completeLocalIdempotentRequest(
                                context,
                                key,
                                BusinessAgentLocalIdempotencyCache.Result.failure(error)
                        );
                    }

                    @Override
                    public void callbackDeliveryFailed(AgentDeliveryResult delivery) {
                        localIdempotency.abandon(key);
                        recordCallbackDelivery(delivery);
                    }
                }
        );
    }

    private <T> void askLocalAgentOnce(
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

    private void completeLocalIdempotentRequest(
            com.commonbattle.actor.ActorContext context,
            BusinessAgentLocalIdempotencyCache.Key key,
            BusinessAgentLocalIdempotencyCache.Result result
    ) {
        List<BusinessAgentLocalIdempotencyCache.Waiter> waiters = localIdempotency.complete(key, result);
        for (BusinessAgentLocalIdempotencyCache.Waiter waiter : waiters) {
            waiter.complete(context, result);
        }
    }

    private static <T> void completeLocalAgentRequest(
            com.commonbattle.actor.ActorContext context,
            BusinessAgentLocalIdempotencyCache.Result result,
            Class<T> responseType,
            LocalAskCallback<T> callback
    ) {
        if (!result.success()) {
            callback.failure(context, result.error());
            return;
        }
        try {
            callback.success(context, responseType.cast(result.response()));
        } catch (RuntimeException e) {
            callback.failure(context, e);
        }
    }

    private <T> void callRemoteAgent(
            ActorRef requester,
            AgentLocation location,
            BusinessAgentRequest request,
            Class<T> responseType,
            BusinessAgentCallOptions options,
            LocalAskCallback<T> callback
    ) {
        messages.callRemote(
                requester,
                RpcRequest.toService(location.serviceId(), BusinessAgentRpcOperations.DISPATCH, request, responseType),
                new RemoteActorCallOptions(options.timeout(), options.callbackCategory()),
                new RemoteActorCallback<>() {
                    @Override
                    public void success(com.commonbattle.actor.ActorContext context, T response) {
                        metrics.remoteSuccessResponse();
                        callback.success(context, response);
                    }

                    @Override
                    public void failure(
                            com.commonbattle.actor.ActorContext context,
                            AgentDeliveryResult delivery,
                            Throwable error
                    ) {
                        if (delivery.status() == com.commonbattle.actor.message.AgentDeliveryStatus.TIMEOUT) {
                            metrics.remoteTimedOutResponse();
                            callback.failure(context, new BusinessAgentRequestTimeoutException(
                                    request.target(),
                                    request.operation(),
                                    options.timeout()
                            ));
                            return;
                        }
                        metrics.remoteFailureResponse();
                        callback.failure(context, error);
                    }

                    @Override
                    public void callbackDeliveryFailed(AgentDeliveryResult delivery) {
                        recordCallbackDelivery(delivery);
                    }

                    @Override
                    public void lateResponse() {
                        metrics.lateRemoteResponse();
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

    private static <T> void completePlayerCommandRequest(
            com.commonbattle.actor.ActorContext context,
            PlayerCommand command,
            PlayerBusinessResponse response,
            Class<T> payloadType,
            LocalAskCallback<T> callback
    ) {
        if (!response.succeeded()) {
            callback.failure(context, new PlayerBusinessCommandRequestException(command, response));
            return;
        }
        Object payload = response.payload();
        if (payload != null && !payloadType.isInstance(payload)) {
            callback.failure(context, new PlayerBusinessCommandPayloadTypeException(command, payloadType, payload.getClass()));
            return;
        }
        callback.success(context, payloadType.cast(payload));
    }

    @Override
    public void close() {
    }

    private void recordCallbackDelivery(AgentDeliveryResult delivery) {
        if (!delivery.accepted()) {
            metrics.callbackDeliveryFailed(delivery.status());
        }
    }
}
