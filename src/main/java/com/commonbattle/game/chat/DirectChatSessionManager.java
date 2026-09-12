package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.AgentMessagePort;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 私聊会话管理器。
 * 只定位会话 Actor，不直接保存私聊业务状态。
 */
public final class DirectChatSessionManager implements ChatRuntimeView {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final ChatMessagePolicy messagePolicy;
    private final ChatDeliverySink deliverySink;
    private final Clock clock;
    private final int maxHistoryMessages;
    private final ConcurrentMap<String, DirectChatSessionAgent> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sendRequests = new AtomicLong();

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this(actors, messages, messagePolicy, ChatDeliverySink.noop(), clock, ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ChatMessagePolicy messagePolicy,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(actors, messages, messagePolicy, ChatDeliverySink.noop(), clock, maxHistoryMessages);
    }

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.deliverySink = Objects.requireNonNull(deliverySink, "deliverySink");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("maxHistoryMessages must be positive");
        }
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public void send(DirectChatSendRequest request, Consumer<ChatSendResult> callback) {
        sendRequests.incrementAndGet();
        session(request.senderId(), request.receiverId()).send(request, callback);
    }

    public long activeSessions() {
        return sessions.size();
    }

    public long sendRequests() {
        return sendRequests.get();
    }

    @Override
    public ChatServiceStats stats() {
        long retainedMessages = 0;
        long droppedHistoryMessages = 0;
        ChatDeliveryResult deliveryStats = ChatDeliveryResult.empty();
        for (DirectChatSessionAgent session : sessions.values()) {
            retainedMessages += session.retainedMessages();
            droppedHistoryMessages += session.droppedHistoryMessages();
            deliveryStats = deliveryStats.plus(session.deliveryStats());
        }
        return new ChatServiceStats(
                0,
                sessions.size(),
                0,
                0,
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

    DirectChatSessionAgent session(long firstPlayerId, long secondPlayerId) {
        String sessionId = ChatChannelIds.direct(firstPlayerId, secondPlayerId);
        return sessions.computeIfAbsent(sessionId, ignored -> create(firstPlayerId, secondPlayerId));
    }

    private DirectChatSessionAgent create(long firstPlayerId, long secondPlayerId) {
        String sessionId = ChatChannelIds.direct(firstPlayerId, secondPlayerId);
        return new DirectChatSessionAgent(
                messages,
                actors.actor("chat-" + sessionId),
                firstPlayerId,
                secondPlayerId,
                messagePolicy,
                deliverySink,
                clock,
                maxHistoryMessages
        );
    }
}
