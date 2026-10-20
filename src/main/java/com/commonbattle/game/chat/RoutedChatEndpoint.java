package com.commonbattle.game.chat;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.remote.AgentOwnerRpcRouter;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcResponder;
import com.commonbattle.cluster.rpc.RpcStructuredException;
import com.commonbattle.game.event.EventGatedCommandAuditSink;
import com.commonbattle.game.event.EventGatedCommandExecutor;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Chat 业务语义 RPC 端点。
 * 世界、联盟、私聊请求在这里解析目标实体，随后仍然只投递到对应 Actor mailbox。
 */
public final class RoutedChatEndpoint {
    private final RoutedChatService chat;
    private final EventGatedCommandExecutor eventGatedCommands;
    private final AgentOwnerRpcRouter ownerRouter;

    public RoutedChatEndpoint(RoutedChatService chat) {
        this(chat, EventGatedCommandAuditSink.noop(), Clock.systemUTC());
    }

    public RoutedChatEndpoint(
            RoutedChatService chat,
            EventGatedCommandAuditSink eventGatedAudits,
            Clock clock
    ) {
        this(chat, new EventGatedCommandExecutor(eventGatedAudits, clock));
    }

    public RoutedChatEndpoint(
            RoutedChatService chat,
            EventGatedCommandExecutor eventGatedCommands
    ) {
        this(chat, eventGatedCommands, null);
    }

    public RoutedChatEndpoint(
            RoutedChatService chat,
            EventGatedCommandAuditSink eventGatedAudits,
            Clock clock,
            AgentOwnerRpcRouter ownerRouter
    ) {
        this(chat, new EventGatedCommandExecutor(eventGatedAudits, clock), ownerRouter);
    }

    public RoutedChatEndpoint(
            RoutedChatService chat,
            EventGatedCommandExecutor eventGatedCommands,
            AgentOwnerRpcRouter ownerRouter
    ) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.eventGatedCommands = Objects.requireNonNull(eventGatedCommands, "eventGatedCommands");
        this.ownerRouter = ownerRouter;
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(ChatOperations.JOIN_WORLD, (request, responder) -> {
            WorldChatJoinRequest payload = (WorldChatJoinRequest) request.payload();
            route(channel(chat.worldChannelId(payload.worldId(), payload.playerId())), ChatOperations.JOIN_WORLD,
                    payload, ChatJoinResult.class, responder,
                    () -> chat.joinWorld(payload, result -> respond(responder, result)));
        });
        gateway.handle(ChatOperations.LEAVE_WORLD, (request, responder) -> {
            WorldChatLeaveRequest payload = (WorldChatLeaveRequest) request.payload();
            route(channel(chat.worldChannelId(payload.worldId(), payload.playerId())), ChatOperations.LEAVE_WORLD,
                    payload, ChatLeaveResult.class, responder,
                    () -> chat.leaveWorld(payload, result -> respond(responder, result)));
        });
        gateway.handle(ChatOperations.SEND_WORLD, (request, responder) -> {
            WorldChatSendRequest payload = (WorldChatSendRequest) request.payload();
            route(channel(chat.worldChannelId(payload.worldId(), payload.senderId())), ChatOperations.SEND_WORLD,
                    payload, ChatSendResult.class, responder,
                    () -> chat.sendWorld(payload, result -> respond(responder, result)));
        });
        gateway.handle(ChatOperations.JOIN_ALLIANCE, (request, responder) -> {
            AllianceChatJoinRequest payload = (AllianceChatJoinRequest) request.payload();
            route(alliance(payload.allianceId()), ChatOperations.JOIN_ALLIANCE,
                    payload, ChatJoinResult.class, responder,
                    () -> chat.joinAlliance(payload, result -> respond(responder, result)));
        });
        gateway.handle(ChatOperations.LEAVE_ALLIANCE, (request, responder) -> {
            AllianceChatLeaveRequest payload = (AllianceChatLeaveRequest) request.payload();
            route(alliance(payload.allianceId()), ChatOperations.LEAVE_ALLIANCE,
                    payload, ChatLeaveResult.class, responder,
                    () -> chat.leaveAlliance(payload, result -> respond(responder, result)));
        });
        gateway.handle(ChatOperations.SEND_ALLIANCE, (request, responder) -> {
            AllianceChatSendRequest payload = (AllianceChatSendRequest) request.payload();
            route(alliance(payload.allianceId()), ChatOperations.SEND_ALLIANCE,
                    payload, ChatSendResult.class, responder,
                    () -> chat.sendAlliance(payload, result -> respond(responder, result)));
        });
        gateway.handle(ChatOperations.SEND_DIRECT, (request, responder) -> {
            DirectChatSendRequest payload = (DirectChatSendRequest) request.payload();
            route(direct(payload), ChatOperations.SEND_DIRECT,
                    payload, ChatSendResult.class, responder,
                    () -> chat.sendDirect(payload, result -> respond(responder, result)));
        });
        gateway.handle(ChatOperations.JOIN_ALLIANCE_AFTER_EVENT, (request, responder) -> {
            AllianceChatJoinAfterEventRequest payload = (AllianceChatJoinAfterEventRequest) request.payload();
            route(alliance(payload.command().allianceId()), ChatOperations.JOIN_ALLIANCE_AFTER_EVENT,
                    payload, ChatJoinResult.class, responder,
                    () -> eventGatedCommands.<AllianceChatJoinAfterEventRequest, ChatJoinResult>execute(
                            ChatOperations.JOIN_ALLIANCE_AFTER_EVENT,
                            payload,
                            (command, callback) -> chat.joinAllianceAfterChanged(
                                    command.event(), command.command(), callback
                            ),
                            result -> result.status().name(),
                            result -> respond(responder, result)
                    ));
        });
        gateway.handle(ChatOperations.SEND_ALLIANCE_AFTER_EVENT, (request, responder) -> {
            AllianceChatSendAfterEventRequest payload = (AllianceChatSendAfterEventRequest) request.payload();
            route(alliance(payload.command().allianceId()), ChatOperations.SEND_ALLIANCE_AFTER_EVENT,
                    payload, ChatSendResult.class, responder,
                    () -> eventGatedCommands.<AllianceChatSendAfterEventRequest, ChatSendResult>execute(
                            ChatOperations.SEND_ALLIANCE_AFTER_EVENT,
                            payload,
                            (command, callback) -> chat.sendAllianceAfterChanged(
                                    command.event(), command.command(), callback
                            ),
                            result -> result.status().name(),
                            result -> respond(responder, result)
                    ));
        });
        gateway.handle(ChatOperations.SEND_DIRECT_AFTER_FRIEND_EVENT, (request, responder) -> {
            DirectChatSendAfterFriendEventRequest payload = (DirectChatSendAfterFriendEventRequest) request.payload();
            route(direct(payload.command()), ChatOperations.SEND_DIRECT_AFTER_FRIEND_EVENT,
                    payload, ChatSendResult.class, responder,
                    () -> eventGatedCommands.<DirectChatSendAfterFriendEventRequest, ChatSendResult>execute(
                            ChatOperations.SEND_DIRECT_AFTER_FRIEND_EVENT,
                            payload,
                            (command, callback) -> chat.sendDirectAfterFriendChanged(
                                    command.event(), command.command(), callback
                            ),
                            result -> result.status().name(),
                            result -> respond(responder, result)
                    ));
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

    private static AgentIdentity channel(String channelId) {
        return ChatActorIds.channelIdentity(channelId);
    }

    private static AgentIdentity alliance(long allianceId) {
        return channel(ChatChannelIds.alliance(allianceId));
    }

    private static AgentIdentity direct(DirectChatSendRequest request) {
        return ChatActorIds.directIdentity(request.senderId(), request.receiverId());
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
