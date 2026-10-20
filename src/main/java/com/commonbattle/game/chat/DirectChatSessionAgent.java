package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 私聊会话 Actor。
 * 两个玩家之间的私聊拥有独立 mailbox，后续可在这里接离线盒子、屏蔽关系和已读水位。
 */
public final class DirectChatSessionAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final String sessionId;
    private final long firstPlayerId;
    private final long secondPlayerId;
    private final DirectChatAccessPolicy accessPolicy;
    private final ChatMessagePolicy messagePolicy;
    private final ChatDeliverySink deliverySink;
    private final Clock clock;
    private final int maxHistoryMessages;
    private final List<ChatDelivery> history = new ArrayList<>();
    private long revision;
    private long pendingDroppedHistoryMessages;
    private volatile long retainedMessages;
    private volatile long droppedHistoryMessages;
    private volatile ChatDeliveryResult deliveryStats = ChatDeliveryResult.empty();
    private ChatAgentPersistence persistence;

    public DirectChatSessionAgent(
            AgentMessagePort messages,
            ActorRef self,
            long firstPlayerId,
            long secondPlayerId,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this(messages, self, firstPlayerId, secondPlayerId, DirectChatAccessPolicy.allowAll(), messagePolicy,
                ChatDeliverySink.noop(), clock,
                ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public DirectChatSessionAgent(
            AgentMessagePort messages,
            ActorRef self,
            long firstPlayerId,
            long secondPlayerId,
            ChatMessagePolicy messagePolicy,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(messages, self, firstPlayerId, secondPlayerId, DirectChatAccessPolicy.allowAll(), messagePolicy,
                ChatDeliverySink.noop(), clock,
                maxHistoryMessages);
    }

    public DirectChatSessionAgent(
            AgentMessagePort messages,
            ActorRef self,
            long firstPlayerId,
            long secondPlayerId,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(messages, self, firstPlayerId, secondPlayerId, DirectChatAccessPolicy.allowAll(), messagePolicy,
                deliverySink, clock, maxHistoryMessages);
    }

    public DirectChatSessionAgent(
            AgentMessagePort messages,
            ActorRef self,
            long firstPlayerId,
            long secondPlayerId,
            DirectChatAccessPolicy accessPolicy,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.firstPlayerId = Math.min(firstPlayerId, secondPlayerId);
        this.secondPlayerId = Math.max(firstPlayerId, secondPlayerId);
        this.sessionId = ChatChannelIds.direct(firstPlayerId, secondPlayerId);
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.deliverySink = Objects.requireNonNull(deliverySink, "deliverySink");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("maxHistoryMessages must be positive");
        }
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public void send(DirectChatSendRequest request, Consumer<ChatSendResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        ensureParticipant(request.senderId());
        ensureParticipant(request.receiverId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND,
                ignored -> executeSend(() -> sendNow(request), callback));
    }

    public void sendAfterAccessStatus(
            DirectChatSendRequest request,
            ChatSendStatus accessStatus,
            Consumer<ChatSendResult> callback
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(accessStatus, "accessStatus");
        Objects.requireNonNull(callback, "callback");
        ensureParticipant(request.senderId());
        ensureParticipant(request.receiverId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND,
                ignored -> executeSend(() -> sendNow(request, accessStatus), callback));
    }

    public AgentDeliveryResult trySendAfterAccessStatus(
            DirectChatSendRequest request,
            ChatSendStatus accessStatus,
            Consumer<ChatSendResult> callback
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(accessStatus, "accessStatus");
        Objects.requireNonNull(callback, "callback");
        ensureParticipant(request.senderId());
        ensureParticipant(request.receiverId());
        return messages.tryTellLocal(self, ActorTaskCategory.PLAYER_COMMAND,
                ignored -> executeSend(() -> sendNow(request, accessStatus), callback));
    }

    public ChatChannelSnapshot snapshotNow() {
        return new ChatChannelSnapshot(sessionId, java.util.Set.of(firstPlayerId, secondPlayerId), history, revision);
    }

    void attachPersistence(ChatAgentPersistence persistence) {
        if (this.persistence != null) {
            throw new IllegalStateException("chat persistence already attached");
        }
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        persistence.start();
    }

    void restoreSnapshot(ChatChannelSnapshot restored) {
        if (!sessionId.equals(restored.channelId())) {
            throw new IllegalArgumentException("direct chat snapshot session mismatch");
        }
        java.util.Set<Long> expectedMembers = java.util.Set.of(firstPlayerId, secondPlayerId);
        if (!expectedMembers.equals(restored.members())) {
            throw new IllegalArgumentException("direct chat snapshot participants mismatch");
        }
        history.clear();
        history.addAll(restored.history());
        revision = restored.revision();
        pendingDroppedHistoryMessages = 0;
        publishCommittedState();
    }

    private ChatSendResult sendNow(DirectChatSendRequest request) {
        return sendNow(request, accessPolicy.inspect(request));
    }

    private ChatSendResult sendNow(DirectChatSendRequest request, ChatSendStatus accessStatus) {
        if (accessStatus != ChatSendStatus.SENT) {
            return ChatSendResult.rejected(accessStatus);
        }
        ChatSendRequest policyRequest = new ChatSendRequest(
                sessionId,
                request.senderId(),
                request.text(),
                request.requiredProfileRevision()
        );
        ChatMessageDecision decision = messagePolicy.inspect(policyRequest);
        if (decision.status() != ChatSendStatus.SENT) {
            return ChatSendResult.rejected(decision.status());
        }
        // 私聊会话结算边界：双方会话 Actor 独占递增 revision，后续离线投递也应从这里排队。
        ChatDelivery delivery = new ChatDelivery(
                sessionId,
                request.senderId(),
                decision.displayName(),
                decision.normalizedText(),
                ++revision,
                clock.instant()
        );
        history.add(delivery);
        trimHistory();
        return ChatSendResult.sent(delivery);
    }

    private void executeSend(Supplier<ChatSendResult> mutation, Consumer<ChatSendResult> callback) {
        executeMutation(
                mutation,
                callback,
                error -> ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED),
                result -> result.delivery().ifPresent(delivery -> deliveryStats = deliveryStats.plus(
                        deliverySink.deliver(new ChatDeliveryEnvelope(
                                sessionId,
                                java.util.Set.of(firstPlayerId, secondPlayerId),
                                delivery
                        ))
                ))
        );
    }

    private <T> void executeMutation(
            Supplier<T> mutation,
            Consumer<T> callback,
            Function<Throwable, T> failureResult,
            Consumer<T> afterCommit
    ) {
        ChatAgentPersistence activePersistence = persistence;
        if (activePersistence == null) {
            T result = mutation.get();
            publishCommittedState();
            afterCommit.accept(result);
            callback.accept(result);
            return;
        }
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        activePersistence.submit(
                () -> result.set(mutation.get()),
                () -> {
                    T committed = result.get();
                    publishCommittedState();
                    afterCommit.accept(committed);
                    callback.accept(committed);
                },
                error -> callback.accept(failureResult.apply(error))
        );
    }

    long retainedMessages() {
        return retainedMessages;
    }

    long droppedHistoryMessages() {
        return droppedHistoryMessages;
    }

    ChatDeliveryResult deliveryStats() {
        return deliveryStats;
    }

    ChatPersistenceStats persistenceStats() {
        return persistence == null ? ChatPersistenceStats.empty() : persistence.stats();
    }

    private void trimHistory() {
        while (history.size() > maxHistoryMessages) {
            history.removeFirst();
            pendingDroppedHistoryMessages++;
        }
    }

    private void publishCommittedState() {
        droppedHistoryMessages += pendingDroppedHistoryMessages;
        pendingDroppedHistoryMessages = 0;
        retainedMessages = history.size();
    }

    private void ensureParticipant(long playerId) {
        if (playerId != firstPlayerId && playerId != secondPlayerId) {
            throw new IllegalArgumentException("player is not a direct chat participant");
        }
    }
}
