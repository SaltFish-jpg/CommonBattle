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
public final class ChatChannelManager implements ChatRuntimeView {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final ScenePlayerInterestCoordinator interests;
    private final ChatMessagePolicy messagePolicy;
    private final ChatDeliverySink deliverySink;
    private final Clock clock;
    private final int maxHistoryMessages;
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
        this(actors, messages, interests, new ProfileAwareChatMessagePolicy(profiles), ChatDeliverySink.noop(), clock,
                ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this(actors, messages, interests, messagePolicy, ChatDeliverySink.noop(), clock, ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.interests = Objects.requireNonNull(interests, "interests");
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.deliverySink = Objects.requireNonNull(deliverySink, "deliverySink");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("maxHistoryMessages must be positive");
        }
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(actors, messages, interests, messagePolicy, ChatDeliverySink.noop(), clock, maxHistoryMessages);
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
        long retainedMessages = 0;
        long droppedHistoryMessages = 0;
        ChatDeliveryResult deliveryStats = ChatDeliveryResult.empty();
        for (ChatChannelAgent channel : channels.values()) {
            retainedMessages += channel.retainedMessages();
            droppedHistoryMessages += channel.droppedHistoryMessages();
            deliveryStats = deliveryStats.plus(channel.deliveryStats());
        }
        return new ChatServiceStats(
                channels.size(),
                0,
                joinRequests.get(),
                leaveRequests.get(),
                sendRequests.get(),
                0,
                0,
                retainedMessages,
                droppedHistoryMessages,
                deliveryStats.acceptedRecipients(),
                deliveryStats.droppedRecipients(),
                deliveryStats.failedRecipients()
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
                deliverySink,
                clock,
                maxHistoryMessages
        );
    }
}
