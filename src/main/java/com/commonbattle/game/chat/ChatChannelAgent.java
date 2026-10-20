package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.scene.ScenePlayerInterest;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 频道聊天 Actor。
 * 每个频道一个 mailbox，成员变更、发言序号和历史追加都只在该 Actor 内串行执行。
 */
public final class ChatChannelAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final String channelId;
    private final ScenePlayerInterestCoordinator interests;
    private final ChatChannelAccessPolicy accessPolicy;
    private final ChatMessagePolicy messagePolicy;
    private final ChatDeliverySink deliverySink;
    private final Clock clock;
    private final int maxHistoryMessages;
    private final Set<Long> members = new HashSet<>();
    private final Map<Long, Long> memberAllianceIds = new java.util.HashMap<>();
    private final Set<Long> interestedMembers = new HashSet<>();
    private final List<ChatDelivery> history = new ArrayList<>();
    private long revision;
    private long pendingDroppedHistoryMessages;
    private volatile long retainedMessages;
    private volatile long droppedHistoryMessages;
    private volatile long allianceEventRemovedMembers;
    private volatile long allianceSnapshotRemovedMembers;
    private volatile ChatDeliveryResult deliveryStats = ChatDeliveryResult.empty();
    private ChatAgentPersistence persistence;

    public ChatChannelAgent(
            AgentMessagePort messages,
            ActorRef self,
            String channelId,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this(messages, self, channelId, interests, ChatChannelAccessPolicy.allowAll(), messagePolicy,
                ChatDeliverySink.noop(), clock,
                ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public ChatChannelAgent(
            AgentMessagePort messages,
            ActorRef self,
            String channelId,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(messages, self, channelId, interests, ChatChannelAccessPolicy.allowAll(), messagePolicy,
                ChatDeliverySink.noop(), clock, maxHistoryMessages);
    }

    public ChatChannelAgent(
            AgentMessagePort messages,
            ActorRef self,
            String channelId,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(messages, self, channelId, interests, ChatChannelAccessPolicy.allowAll(), messagePolicy,
                deliverySink, clock, maxHistoryMessages);
    }

    public ChatChannelAgent(
            AgentMessagePort messages,
            ActorRef self,
            String channelId,
            ScenePlayerInterestCoordinator interests,
            ChatChannelAccessPolicy accessPolicy,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.channelId = ChatJoinRequest.normalizeChannelId(channelId);
        this.interests = Objects.requireNonNull(interests, "interests");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.deliverySink = Objects.requireNonNull(deliverySink, "deliverySink");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("maxHistoryMessages must be positive");
        }
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public void join(ChatJoinRequest request, Consumer<ChatJoinResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND, ignored -> executeMutation(
                () -> joinNow(request), callback,
                error -> new ChatJoinResult(ChatJoinStatus.BACKPRESSURED, channelId, request.playerId(), members.size()),
                ignoredResult -> {
                }
        ));
    }

    public void joinAfterAccessStatus(
            ChatJoinRequest request,
            ChatJoinStatus accessStatus,
            Consumer<ChatJoinResult> callback
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(accessStatus, "accessStatus");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND, ignored -> executeMutation(
                () -> joinNow(request, accessStatus), callback,
                error -> new ChatJoinResult(ChatJoinStatus.BACKPRESSURED, channelId, request.playerId(), members.size()),
                ignoredResult -> {
                }
        ));
    }

    public AgentDeliveryResult tryJoinAfterAccessStatus(
            ChatJoinRequest request,
            ChatJoinStatus accessStatus,
            Consumer<ChatJoinResult> callback
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(accessStatus, "accessStatus");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        return messages.tryTellLocal(self, ActorTaskCategory.PLAYER_COMMAND, ignored -> executeMutation(
                () -> joinNow(request, accessStatus), callback,
                error -> new ChatJoinResult(ChatJoinStatus.BACKPRESSURED, channelId, request.playerId(), members.size()),
                ignoredResult -> {
                }
        ));
    }

    public void leave(ChatLeaveRequest request, Consumer<ChatLeaveResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND, ignored -> executeMutation(
                () -> leaveNow(request), callback,
                error -> new ChatLeaveResult(ChatLeaveStatus.BACKPRESSURED, channelId, request.playerId(), members.size()),
                ignoredResult -> {
                }
        ));
    }

    public void send(ChatSendRequest request, Consumer<ChatSendResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND,
                ignored -> executeSend(() -> sendNow(request), callback));
    }

    public void sendAfterAccessStatus(
            ChatSendRequest request,
            ChatSendStatus accessStatus,
            Consumer<ChatSendResult> callback
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(accessStatus, "accessStatus");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND,
                ignored -> executeSend(() -> sendNow(request, accessStatus), callback));
    }

    public AgentDeliveryResult trySendAfterAccessStatus(
            ChatSendRequest request,
            ChatSendStatus accessStatus,
            Consumer<ChatSendResult> callback
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(accessStatus, "accessStatus");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        return messages.tryTellLocal(self, ActorTaskCategory.PLAYER_COMMAND,
                ignored -> executeSend(() -> sendNow(request, accessStatus), callback));
    }

    public void sendAfterMessageDecision(
            ChatSendRequest request,
            ChatMessageDecision decision,
            Consumer<ChatSendResult> callback
    ) {
        sendAfterAccessAndMessageDecision(request, ChatSendStatus.SENT, decision, callback);
    }

    public void sendAfterAccessAndMessageDecision(
            ChatSendRequest request,
            ChatSendStatus accessStatus,
            ChatMessageDecision decision,
            Consumer<ChatSendResult> callback
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(accessStatus, "accessStatus");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        messages.tellLocal(self, ActorTaskCategory.PLAYER_COMMAND,
                ignored -> executeSend(() -> sendNow(request, accessStatus, decision), callback));
    }

    public AgentDeliveryResult trySendAfterMessageDecision(
            ChatSendRequest request,
            ChatMessageDecision decision,
            Consumer<ChatSendResult> callback
    ) {
        return trySendAfterAccessAndMessageDecision(request, ChatSendStatus.SENT, decision, callback);
    }

    public AgentDeliveryResult trySendAfterAccessAndMessageDecision(
            ChatSendRequest request,
            ChatSendStatus accessStatus,
            ChatMessageDecision decision,
            Consumer<ChatSendResult> callback
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(accessStatus, "accessStatus");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(callback, "callback");
        ensureSameChannel(request.channelId());
        return messages.tryTellLocal(self, ActorTaskCategory.PLAYER_COMMAND,
                ignored -> executeSend(() -> sendNow(request, accessStatus, decision), callback));
    }

    public void snapshot(Consumer<ChatChannelSnapshot> callback) {
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ActorTaskCategory.OBSERVABILITY, ignored -> executeMutation(
                this::snapshotNow,
                callback,
                error -> snapshotNow(),
                ignoredSnapshot -> {
                }
        ));
    }

    public void removeMemberDueToAllianceChange(long playerId) {
        messages.tellLocal(self, ActorTaskCategory.EVENT, ignored -> executeMutation(
                () -> removeMemberNow(playerId),
                removed -> {
                },
                error -> false,
                removed -> {
                    if (removed) {
                        allianceEventRemovedMembers++;
                    }
                }
        ));
    }

    public void retainMembersDueToAllianceSnapshot(Set<Long> memberIds) {
        Set<Long> retained = Set.copyOf(Objects.requireNonNull(memberIds, "memberIds"));
        messages.tellLocal(self, ActorTaskCategory.EVENT, ignored -> executeMutation(
                () -> retainMembersNow(retained),
                removed -> {
                },
                error -> 0L,
                removed -> allianceSnapshotRemovedMembers += removed
        ));
    }

    public ChatChannelSnapshot snapshotNow() {
        return new ChatChannelSnapshot(channelId, members, history, revision, memberAllianceIds);
    }

    void attachPersistence(ChatAgentPersistence persistence) {
        if (this.persistence != null) {
            throw new IllegalStateException("chat persistence already attached");
        }
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        persistence.start();
    }

    private ChatJoinResult joinNow(ChatJoinRequest request) {
        return joinNow(request, accessPolicy.inspectJoin(request));
    }

    private ChatJoinResult joinNow(ChatJoinRequest request, ChatJoinStatus accessStatus) {
        if (accessStatus != ChatJoinStatus.JOINED) {
            return new ChatJoinResult(accessStatus, channelId, request.playerId(), members.size());
        }
        boolean added = members.add(request.playerId());
        if (added) {
            memberAllianceIds.put(request.playerId(), request.allianceId());
        }
        return new ChatJoinResult(
                added ? ChatJoinStatus.JOINED : ChatJoinStatus.ALREADY_JOINED,
                channelId,
                request.playerId(),
                members.size()
        );
    }

    private ChatLeaveResult leaveNow(ChatLeaveRequest request) {
        boolean removed = removeMemberNow(request.playerId());
        return new ChatLeaveResult(
                removed ? ChatLeaveStatus.LEFT : ChatLeaveStatus.NOT_IN_CHANNEL,
                channelId,
                request.playerId(),
                members.size()
        );
    }

    private boolean removeMemberNow(long playerId) {
        boolean removed = members.remove(playerId);
        if (removed) {
            memberAllianceIds.remove(playerId);
        }
        return removed;
    }

    private ChatSendResult sendNow(ChatSendRequest request) {
        if (!members.contains(request.senderId())) {
            return ChatSendResult.rejected(ChatSendStatus.NOT_IN_CHANNEL);
        }
        return sendNow(request, accessPolicy.inspectSend(request));
    }

    private ChatSendResult sendNow(ChatSendRequest request, ChatSendStatus accessStatus) {
        if (!members.contains(request.senderId())) {
            return ChatSendResult.rejected(ChatSendStatus.NOT_IN_CHANNEL);
        }
        if (accessStatus != ChatSendStatus.SENT) {
            return ChatSendResult.rejected(accessStatus);
        }
        return sendNow(request, accessStatus, messagePolicy.inspect(request));
    }

    private ChatSendResult sendNow(ChatSendRequest request, ChatSendStatus accessStatus, ChatMessageDecision decision) {
        if (!members.contains(request.senderId())) {
            return ChatSendResult.rejected(ChatSendStatus.NOT_IN_CHANNEL);
        }
        if (accessStatus != ChatSendStatus.SENT) {
            return ChatSendResult.rejected(accessStatus);
        }
        if (decision.status() != ChatSendStatus.SENT) {
            return ChatSendResult.rejected(decision.status());
        }
        // 发言结算边界：只有频道 Actor 确认成员和资料快照后才递增 revision 并追加历史。
        ChatDelivery delivery = new ChatDelivery(
                channelId,
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

    private long retainMembersNow(Set<Long> retained) {
        long removed = 0;
        for (long memberId : Set.copyOf(members)) {
            if (!retained.contains(memberId) && removeMemberNow(memberId)) {
                removed++;
            }
        }
        return removed;
    }

    void restoreSnapshot(ChatChannelSnapshot restored) {
        if (!channelId.equals(restored.channelId())) {
            throw new IllegalArgumentException("chat snapshot channel mismatch");
        }
        members.clear();
        members.addAll(restored.members());
        memberAllianceIds.clear();
        memberAllianceIds.putAll(restored.memberAllianceIds());
        history.clear();
        history.addAll(restored.history());
        revision = restored.revision();
        pendingDroppedHistoryMessages = 0;
        publishCommittedState();
    }

    private long allianceId() {
        if (!channelId.startsWith("alliance:")) {
            return ScenePlayerInterest.NO_ALLIANCE;
        }
        try {
            return Long.parseLong(channelId.substring("alliance:".length()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("invalid alliance channel id: " + channelId, e);
        }
    }

    private void executeSend(Supplier<ChatSendResult> mutation, Consumer<ChatSendResult> callback) {
        executeMutation(
                mutation,
                callback,
                error -> ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED),
                result -> result.delivery().ifPresent(delivery -> deliveryStats = deliveryStats.plus(
                        deliverySink.deliver(new ChatDeliveryEnvelope(channelId, members, delivery))
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

    long allianceEventRemovedMembers() {
        return allianceEventRemovedMembers;
    }

    long allianceSnapshotRemovedMembers() {
        return allianceSnapshotRemovedMembers;
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
        reconcileInterests();
    }

    private void reconcileInterests() {
        for (long playerId : Set.copyOf(interestedMembers)) {
            if (!members.contains(playerId)) {
                interests.leave(playerId, interestKey());
                interestedMembers.remove(playerId);
            }
        }
        for (long playerId : members) {
            if (interestedMembers.add(playerId)) {
                interests.enter(ScenePlayerInterest.withAlliance(
                        playerId,
                        interestKey(),
                        memberAllianceIds.getOrDefault(playerId, allianceId())
                ));
            }
        }
    }

    private String interestKey() {
        return "chat:" + channelId;
    }

    private void ensureSameChannel(String requestChannelId) {
        if (!channelId.equals(requestChannelId)) {
            throw new IllegalArgumentException("request channel does not match agent channel");
        }
    }
}
