package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.AgentRoute;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.actor.message.LocalAsk;
import com.commonbattle.actor.message.LocalAskCallback;
import com.commonbattle.actor.message.RemoteAgentCallback;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.player.PlayerBusinessCommandGateway;
import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.session.PlayerCommand;

import java.time.Duration;
import java.util.Objects;

/**
 * 默认游戏业务 Agent 通信端口。
 * 本类只做门面聚合：本服 Agent 通信交给 AgentMessagePort，玩家业务命令交给 PlayerBusinessCommandGateway。
 */
public final class DefaultBusinessAgentMessagePort implements BusinessAgentMessagePort {
    private final AgentMessagePort messages;
    private final PlayerBusinessCommandGateway playerCommands;
    private final LifecycleAwareAgentRouter router;
    private final BusinessAgentHandlerRegistry handlers;

    public DefaultBusinessAgentMessagePort(AgentMessagePort messages, PlayerBusinessCommandGateway playerCommands) {
        this(messages, playerCommands, null, null);
    }

    public DefaultBusinessAgentMessagePort(
            AgentMessagePort messages,
            PlayerBusinessCommandGateway playerCommands,
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.playerCommands = Objects.requireNonNull(playerCommands, "playerCommands");
        this.router = router;
        this.handlers = handlers;
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
        BusinessAgentRequest request = new BusinessAgentRequest(target, operation, payload);
        AgentRoute route = router.resolve(target);
        if (route.type() == AgentRouteType.LOCAL) {
            askLocalAgent(requester, route.location().orElseThrow(), request, responseType, options, callback);
            return;
        }
        if (route.type() == AgentRouteType.REMOTE) {
            callRemoteAgent(requester, route.location().orElseThrow(), request, responseType, options, callback);
            return;
        }
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
        messages.callRemote(
                RpcRequest.toService(location.serviceId(), BusinessAgentRpcOperations.DISPATCH, request, responseType),
                new RpcCallback<>() {
                    @Override
                    public void success(T response) {
                        messages.tryTellLocal(requester, options.callbackCategory(),
                                context -> callback.success(context, response));
                    }

                    @Override
                    public void failure(Throwable error) {
                        messages.tryTellLocal(requester, options.callbackCategory(),
                                context -> callback.failure(context, error));
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
        messages.tryTellLocal(requester, options.callbackCategory(), context -> callback.failure(context, error));
    }

    private void ensureAgentRequestConfigured() {
        if (router == null || handlers == null) {
            throw new IllegalStateException("Business agent request routing is not configured");
        }
    }
}
