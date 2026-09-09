package com.commonbattle.game.chat;

import com.commonbattle.cluster.rpc.ClusterRpcGateway;

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
                chat.joinWorld((WorldChatJoinRequest) request.payload(), responder::success));
        gateway.handle(ChatOperations.LEAVE_WORLD, (request, responder) ->
                chat.leaveWorld((WorldChatLeaveRequest) request.payload(), responder::success));
        gateway.handle(ChatOperations.SEND_WORLD, (request, responder) ->
                chat.sendWorld((WorldChatSendRequest) request.payload(), responder::success));
        gateway.handle(ChatOperations.JOIN_ALLIANCE, (request, responder) ->
                chat.joinAlliance((AllianceChatJoinRequest) request.payload(), responder::success));
        gateway.handle(ChatOperations.LEAVE_ALLIANCE, (request, responder) ->
                chat.leaveAlliance((AllianceChatLeaveRequest) request.payload(), responder::success));
        gateway.handle(ChatOperations.SEND_ALLIANCE, (request, responder) ->
                chat.sendAlliance((AllianceChatSendRequest) request.payload(), responder::success));
        gateway.handle(ChatOperations.SEND_DIRECT, (request, responder) ->
                chat.sendDirect((DirectChatSendRequest) request.payload(), responder::success));
    }
}
