package com.commonbattle.game.player;

import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandStatus;

import java.util.Objects;

/**
 * 玩家业务命令统一入口。
 * 本服玩家命令进入本地邮箱；远端玩家命令按 Agent owner 转发到目标 Game 服，调用方始终收到统一响应信封。
 */
public final class PlayerBusinessCommandGateway {
    private final PlayerCommandDispatcher dispatcher;
    private final RpcGateway rpc;
    private final PlayerBusinessResponseHub responses;

    public PlayerBusinessCommandGateway(
            PlayerCommandDispatcher dispatcher,
            RpcGateway rpc,
            PlayerBusinessResponseHub responses
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    public void submit(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback) {
        dispatch(command, callback, true);
    }

    public void submitLocalOnly(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback) {
        dispatch(command, callback, false);
    }

    private void dispatch(
            PlayerCommand command,
            RpcCallback<PlayerBusinessResponse> callback,
            boolean allowRemoteForward
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(callback, "callback");
        PlayerBusinessResponseRegistration registration;
        try {
            registration = responses.expect(command, callback::success);
        } catch (RuntimeException e) {
            callback.failure(e);
            return;
        }
        PlayerCommandResult result;
        try {
            result = dispatcher.dispatch(command);
        } catch (RuntimeException e) {
            registration.cancel();
            callback.failure(e);
            return;
        }
        if (result.status() == PlayerCommandStatus.ACCEPTED) {
            return;
        }
        registration.cancel();
        if (result.status() == PlayerCommandStatus.ROUTED_REMOTE && allowRemoteForward) {
            try {
                forwardRemote(command, result, callback);
            } catch (RuntimeException e) {
                callback.failure(e);
            }
            return;
        }
        callback.success(PlayerBusinessResponse.failure(command, new PlayerCommandDispatchException(result)));
    }

    private void forwardRemote(
            PlayerCommand command,
            PlayerCommandResult result,
            RpcCallback<PlayerBusinessResponse> callback
    ) {
        AgentLocation location = result.route()
                .flatMap(route -> route.location())
                .orElseThrow(() -> new IllegalStateException("remote player command has no target location"));
        rpc.call(
                RpcRequest.toService(
                        location.serviceId(),
                        PlayerBusinessRpcOperations.DISPATCH,
                        command,
                        PlayerBusinessResponse.class
                ),
                callback
        );
    }
}
