package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.profile.ProfileReadPort;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Chat 服频道管理器。
 * Manager 只负责定位频道 Actor，具体业务状态不放在 Manager，避免绕过 mailbox 串行边界。
 */
public final class ChatChannelManager {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final ScenePlayerInterestCoordinator interests;
    private final ChatMessagePolicy messagePolicy;
    private final Clock clock;
    private final ConcurrentMap<String, ChatChannelAgent> channels = new ConcurrentHashMap<>();
    private final AtomicLong joinRequests = new AtomicLong();
    private final AtomicLong leaveRequests = new AtomicLong();
    private final AtomicLong sendRequests = new AtomicLong();

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ProfileReadPort profiles,
            Clock clock
    ) {
        this(actors, messages, interests, new ProfileAwareChatMessagePolicy(profiles), clock);
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.interests = Objects.requireNonNull(interests, "interests");
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void join(ChatJoinRequest request, Consumer<ChatJoinResult> callback) {
        joinRequests.incrementAndGet();
        channel(request.channelId()).join(request, callback);
    }

    public void leave(ChatLeaveRequest request, Consumer<ChatLeaveResult> callback) {
        leaveRequests.incrementAndGet();
        channel(request.channelId()).leave(request, callback);
    }

    public void send(ChatSendRequest request, Consumer<ChatSendResult> callback) {
        sendRequests.incrementAndGet();
        channel(request.channelId()).send(request, callback);
    }

    public ChatServiceStats stats() {
        return new ChatServiceStats(
                channels.size(),
                joinRequests.get(),
                leaveRequests.get(),
                sendRequests.get()
        );
    }

    ChatChannelAgent channel(String channelId) {
        String normalized = ChatJoinRequest.normalizeChannelId(channelId);
        return channels.computeIfAbsent(normalized, this::createChannel);
    }

    private ChatChannelAgent createChannel(String channelId) {
        return new ChatChannelAgent(
                messages,
                actors.actor("chat-channel-" + channelId),
                channelId,
                interests,
                messagePolicy,
                clock
        );
    }
}
