package com.commonbattle.game.chat;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 游戏语义层 Chat 路由服务。
 * 对外暴露世界、联盟、私聊，内部把它们稳定映射到对应业务实体 Actor。
 */
public final class RoutedChatService {
    private final ChatChannelManager channels;
    private final DirectChatSessionManager directSessions;
    private final ChatRouteConfig config;

    public RoutedChatService(
            ChatChannelManager channels,
            DirectChatSessionManager directSessions,
            ChatRouteConfig config
    ) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.directSessions = Objects.requireNonNull(directSessions, "directSessions");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void joinWorld(WorldChatJoinRequest request, Consumer<ChatJoinResult> callback) {
        channels.join(new ChatJoinRequest(
                worldChannelId(request.worldId(), request.playerId()),
                request.playerId(),
                request.allianceId()
        ), callback);
    }

    public void leaveWorld(WorldChatLeaveRequest request, Consumer<ChatLeaveResult> callback) {
        channels.leave(new ChatLeaveRequest(worldChannelId(request.worldId(), request.playerId()), request.playerId()), callback);
    }

    public void sendWorld(WorldChatSendRequest request, Consumer<ChatSendResult> callback) {
        channels.send(new ChatSendRequest(
                worldChannelId(request.worldId(), request.senderId()),
                request.senderId(),
                request.text(),
                request.requiredProfileRevision()
        ), callback);
    }

    public void joinAlliance(AllianceChatJoinRequest request, Consumer<ChatJoinResult> callback) {
        channels.join(new ChatJoinRequest(ChatChannelIds.alliance(request.allianceId()), request.playerId(), request.allianceId()), callback);
    }

    public void leaveAlliance(AllianceChatLeaveRequest request, Consumer<ChatLeaveResult> callback) {
        channels.leave(new ChatLeaveRequest(ChatChannelIds.alliance(request.allianceId()), request.playerId()), callback);
    }

    public void sendAlliance(AllianceChatSendRequest request, Consumer<ChatSendResult> callback) {
        channels.send(new ChatSendRequest(
                ChatChannelIds.alliance(request.allianceId()),
                request.senderId(),
                request.text(),
                request.requiredProfileRevision()
        ), callback);
    }

    public void sendDirect(DirectChatSendRequest request, Consumer<ChatSendResult> callback) {
        directSessions.send(request, callback);
    }

    public String worldChannelId(String worldId, long playerId) {
        int shard = Math.floorMod(Long.hashCode(playerId), config.worldShardCount());
        return ChatChannelIds.world(worldId, shard);
    }

    public ChatRouteConfig config() {
        return config;
    }
}
