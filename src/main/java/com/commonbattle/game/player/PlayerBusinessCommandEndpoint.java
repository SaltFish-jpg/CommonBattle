package com.commonbattle.game.player;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.game.session.PlayerCommand;

import java.util.Objects;

/**
 * Game 服玩家业务命令 RPC 端点。
 * 远端转发来的请求只允许在当前 Game 本地落邮箱，避免目录短暂不一致时产生转发环。
 */
public final class PlayerBusinessCommandEndpoint {
    private final PlayerBusinessCommandGateway commands;

    public PlayerBusinessCommandEndpoint(PlayerBusinessCommandGateway commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(PlayerBusinessRpcOperations.DISPATCH, (request, responder) -> {
            PlayerCommand command = (PlayerCommand) request.payload();
            commands.submitLocalOnly(command, new RpcCallback<>() {
                @Override
                public void success(PlayerBusinessResponse response) {
                    responder.success(response);
                }

                @Override
                public void failure(Throwable error) {
                    responder.failure(error);
                }
            });
        });
    }
}
