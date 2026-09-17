package com.commonbattle.game.chat;

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

    public ChatChannelEndpoint(ChatChannelManager channels) {
        this.channels = Objects.requireNonNull(channels, "channels");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(ChatOperations.JOIN_CHANNEL, (request, responder) ->
                channels.join((ChatJoinRequest) request.payload(), result -> respond(responder, result)));
        gateway.handle(ChatOperations.LEAVE_CHANNEL, (request, responder) ->
                channels.leave((ChatLeaveRequest) request.payload(), result -> respond(responder, result)));
        gateway.handle(ChatOperations.SEND_CHANNEL, (request, responder) ->
                channels.send((ChatSendRequest) request.payload(), result -> respond(responder, result)));
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
