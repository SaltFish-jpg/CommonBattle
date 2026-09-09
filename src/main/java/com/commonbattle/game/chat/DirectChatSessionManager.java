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
public final class DirectChatSessionManager {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final ChatMessagePolicy messagePolicy;
    private final Clock clock;
    private final ConcurrentMap<String, DirectChatSessionAgent> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sendRequests = new AtomicLong();

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
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
                clock
        );
    }
}
