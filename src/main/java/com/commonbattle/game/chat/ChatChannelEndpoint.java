package com.commonbattle.game.chat;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.remote.AgentOwnerRpcRouter;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcResponder;
import com.commonbattle.cluster.rpc.RpcStructuredException;

import java.time.Duration;
import java.util.Objects;

/**
 * Chat 频道 RPC 端点。
 * 端点只做协议转换和入队，业务状态必须回到频道 Actor mailbox 内处理。
 */
public final class ChatChannelEndpoint {
    private final ChatChannelManager channels;
    private final AgentOwnerRpcRouter ownerRouter;

    public ChatChannelEndpoint(ChatChannelManager channels) {
        this(channels, null);
    }

    public ChatChannelEndpoint(ChatChannelManager channels, AgentOwnerRpcRouter ownerRouter) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.ownerRouter = ownerRouter;
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(ChatOperations.JOIN_CHANNEL, (request, responder) -> {
            ChatJoinRequest payload = (ChatJoinRequest) request.payload();
            route(
                    ChatActorIds.channelIdentity(payload.channelId()),
                    ChatOperations.JOIN_CHANNEL,
                    payload,
                    ChatJoinResult.class,
                    responder,
                    () -> channels.join(payload, result -> respond(responder, result))
            );
        });
        gateway.handle(ChatOperations.LEAVE_CHANNEL, (request, responder) -> {
            ChatLeaveRequest payload = (ChatLeaveRequest) request.payload();
            route(
                    ChatActorIds.channelIdentity(payload.channelId()),
                    ChatOperations.LEAVE_CHANNEL,
                    payload,
                    ChatLeaveResult.class,
                    responder,
                    () -> channels.leave(payload, result -> respond(responder, result))
            );
        });
        gateway.handle(ChatOperations.SEND_CHANNEL, (request, responder) -> {
            ChatSendRequest payload = (ChatSendRequest) request.payload();
            route(
                    ChatActorIds.channelIdentity(payload.channelId()),
                    ChatOperations.SEND_CHANNEL,
                    payload,
                    ChatSendResult.class,
                    responder,
                    () -> channels.send(payload, result -> respond(responder, result))
            );
        });
    }

    private <T> void route(
            AgentIdentity identity,
            String operation,
            Object payload,
            Class<T> responseType,
            RpcResponder responder,
            Runnable localEnqueue
    ) {
        if (ownerRouter == null) {
            localEnqueue.run();
            return;
        }
        ownerRouter.route(identity, operation, payload, responseType, responder, localEnqueue);
    }

    private static void respond(RpcResponder responder, ChatJoinResult result) {
        if (result.status() == ChatJoinStatus.BACKPRESSURED) {
            responder.failure(RpcStructuredException.rejected("mailbox_pressure:target", Duration.ZERO));
            return;
        }
        responder.success(result);
    }

    private static void respond(RpcResponder responder, ChatLeaveResult result) {
        if (result.status() == ChatLeaveStatus.BACKPRESSURED) {
            responder.failure(RpcStructuredException.rejected("mailbox_pressure:target", Duration.ZERO));
            return;
        }
        responder.success(result);
    }

    private static void respond(RpcResponder responder, ChatSendResult result) {
        if (result.status() == ChatSendStatus.BACKPRESSURED) {
            responder.failure(RpcStructuredException.rejected("mailbox_pressure:target", Duration.ZERO));
            return;
        }
        responder.success(result);
    }
}
