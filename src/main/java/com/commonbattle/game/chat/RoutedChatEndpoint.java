package com.commonbattle.game.chat;

import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcResponder;
import com.commonbattle.cluster.rpc.RpcStructuredException;

import java.time.Duration;
import java.util.Objects;

/**
 * Chat 业务语义 RPC 端点。
 * 世界、联盟、私聊请求在这里解析目标实体，随后仍然只投递到对应 Actor mailbox。
 */
public final class RoutedChatEndpoint {
    private final RoutedChatService chat;

    public RoutedChatEndpoint(RoutedChatService chat) {
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(ChatOperations.JOIN_WORLD, (request, responder) ->
                chat.joinWorld((WorldChatJoinRequest) request.payload(), result -> respond(responder, result)));
        gateway.handle(ChatOperations.LEAVE_WORLD, (request, responder) ->
                chat.leaveWorld((WorldChatLeaveRequest) request.payload(), result -> respond(responder, result)));
        gateway.handle(ChatOperations.SEND_WORLD, (request, responder) ->
                chat.sendWorld((WorldChatSendRequest) request.payload(), result -> respond(responder, result)));
        gateway.handle(ChatOperations.JOIN_ALLIANCE, (request, responder) ->
                chat.joinAlliance((AllianceChatJoinRequest) request.payload(), result -> respond(responder, result)));
        gateway.handle(ChatOperations.LEAVE_ALLIANCE, (request, responder) ->
                chat.leaveAlliance((AllianceChatLeaveRequest) request.payload(), result -> respond(responder, result)));
        gateway.handle(ChatOperations.SEND_ALLIANCE, (request, responder) ->
                chat.sendAlliance((AllianceChatSendRequest) request.payload(), result -> respond(responder, result)));
        gateway.handle(ChatOperations.SEND_DIRECT, (request, responder) ->
                chat.sendDirect((DirectChatSendRequest) request.payload(), result -> respond(responder, result)));
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
