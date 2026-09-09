package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.message.LocalAskCallback;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.session.PlayerCommand;

/**
 * 游戏业务 Agent 通信端口。
 * 业务模块依赖该接口即可同时使用本服 Agent 邮箱通信、远程 RPC，以及玩家 Actor 命令的本地/跨服统一投递。
 */
public interface BusinessAgentMessagePort extends AgentMessagePort {
    <T> void requestAgent(
            ActorRef requester,
            AgentIdentity target,
            String operation,
            Object payload,
            Class<T> responseType,
            BusinessAgentCallOptions options,
            LocalAskCallback<T> callback
    );

    default <T> void requestAgent(
            ActorRef requester,
            AgentIdentity target,
            String operation,
            Object payload,
            Class<T> responseType,
            LocalAskCallback<T> callback
    ) {
        requestAgent(requester, target, operation, payload, responseType, BusinessAgentCallOptions.defaults(), callback);
    }

    void sendPlayerCommand(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback);

    void sendLocalPlayerCommand(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback);
}
