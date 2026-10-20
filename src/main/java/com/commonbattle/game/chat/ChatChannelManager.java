package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.backpressure.InboundAdmissionController;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.agent.AsyncActorCallback;
import com.commonbattle.game.profile.ProfileReadPort;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.social.AllianceMemberChangedEvent;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Chat 服频道管理器。
 * Manager 只负责定位频道 Actor，具体业务状态不放在 Manager，避免绕过 mailbox 串行边界。
 */
public final class ChatChannelManager implements ChatRuntimeView {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final ScenePlayerInterestCoordinator interests;
    private final ChatChannelAccessPolicy accessPolicy;
    private final AsyncChatChannelAccessPolicy asyncAccessPolicy;
    private final ChatMessagePolicy messagePolicy;
    private final AsyncChatMessagePolicy asyncMessagePolicy;
    private final ChatDeliverySink deliverySink;
    private final InboundAdmissionController admissions;
    private final Clock clock;
    private final int maxHistoryMessages;
    private final AgentLifecycleManager lifecycles;
    private final AsyncChatStateStore stateStore;
    private final int persistencePendingCapacity;
    private final ConcurrentMap<String, ChatChannelAgent> channels = new ConcurrentHashMap<>();
    private final AtomicLong joinRequests = new AtomicLong();
    private final AtomicLong leaveRequests = new AtomicLong();
    private final AtomicLong sendRequests = new AtomicLong();
    private final AtomicLong asyncMessageChecks = new AtomicLong();
    private final AtomicLong asyncMessageSentDecisions = new AtomicLong();
    private final AtomicLong asyncMessageRejectedDecisions = new AtomicLong();
    private final AtomicLong asyncMessageBackpressuredDecisions = new AtomicLong();
    private final AtomicLong asyncMessageCallbackDeliveryFailures = new AtomicLong();
    private final AtomicLong asyncAccessChecks = new AtomicLong();
    private final AtomicLong asyncAccessAllowedDecisions = new AtomicLong();
    private final AtomicLong asyncAccessRejectedDecisions = new AtomicLong();
    private final AtomicLong asyncAccessBackpressuredDecisions = new AtomicLong();
    private final AtomicLong asyncAccessCallbackDeliveryFailures = new AtomicLong();
    private final AtomicBoolean draining = new AtomicBoolean();

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
        this(actors, messages, interests, ChatChannelAccessPolicy.allowAll(), messagePolicy, deliverySink, clock,
                maxHistoryMessages,
                (target, operation) -> AdmissionDecision.accept());
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatChannelAccessPolicy accessPolicy,
            AsyncChatMessagePolicy asyncMessagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions
    ) {
        this(
                actors,
                messages,
                interests,
                accessPolicy,
                null,
                ignored -> ChatMessageDecision.rejected(ChatSendStatus.STALE_PROFILE),
                asyncMessagePolicy,
                deliverySink,
                clock,
                maxHistoryMessages,
                admissions
        );
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions
    ) {
        this(actors, messages, interests, ChatChannelAccessPolicy.allowAll(), messagePolicy, deliverySink, clock,
                maxHistoryMessages, admissions);
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatChannelAccessPolicy accessPolicy,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions
    ) {
        this(actors, messages, interests, accessPolicy, null, messagePolicy, null, deliverySink, clock, maxHistoryMessages,
                admissions);
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatChannelAccessPolicy accessPolicy,
            AsyncChatChannelAccessPolicy asyncAccessPolicy,
            ChatMessagePolicy messagePolicy,
            AsyncChatMessagePolicy asyncMessagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions
    ) {
        this(
                actors, messages, interests, accessPolicy, asyncAccessPolicy, messagePolicy, asyncMessagePolicy,
                deliverySink, clock, maxHistoryMessages, admissions, null
        );
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatChannelAccessPolicy accessPolicy,
            AsyncChatChannelAccessPolicy asyncAccessPolicy,
            ChatMessagePolicy messagePolicy,
            AsyncChatMessagePolicy asyncMessagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions,
            AgentLifecycleManager lifecycles
    ) {
        this(
                actors, messages, interests, accessPolicy, asyncAccessPolicy, messagePolicy, asyncMessagePolicy,
                deliverySink, clock, maxHistoryMessages, admissions, lifecycles, null, 256
        );
    }

    public ChatChannelManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            ChatChannelAccessPolicy accessPolicy,
            AsyncChatChannelAccessPolicy asyncAccessPolicy,
            ChatMessagePolicy messagePolicy,
            AsyncChatMessagePolicy asyncMessagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions,
            AgentLifecycleManager lifecycles,
            AsyncChatStateStore stateStore,
            int persistencePendingCapacity
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.interests = Objects.requireNonNull(interests, "interests");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.asyncAccessPolicy = asyncAccessPolicy;
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
        this.asyncMessagePolicy = asyncMessagePolicy;
        this.deliverySink = Objects.requireNonNull(deliverySink, "deliverySink");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifecycles = lifecycles;
        this.stateStore = stateStore;
        if (persistencePendingCapacity <= 0) {
            throw new IllegalArgumentException("persistencePendingCapacity must be positive");
        }
        this.persistencePendingCapacity = persistencePendingCapacity;
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("maxHistoryMessages must be positive");
        }
        this.maxHistoryMessages = maxHistoryMessages;
        if (lifecycles != null) {
            lifecycles.addInvalidationListener(record -> {
                if (ChatActorIds.CHANNEL_TYPE.equals(record.identity().type())) {
                    channels.remove(record.identity().key());
                }
            });
        }
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
        if (rejectJoinWhileDraining(request, callback)) {
            return;
        }
        joinRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.channelIdentity(request.channelId()),
                ChatOperations.JOIN_CHANNEL);
        if (!admission.accepted()) {
            callback.accept(new ChatJoinResult(ChatJoinStatus.BACKPRESSURED, request.channelId(), request.playerId(), 0));
            return;
        }
        ChatChannelAgent channel = channel(request.channelId());
        if (asyncAccessPolicy == null) {
            channel.join(request, callback);
            return;
        }
        asyncAccessChecks.incrementAndGet();
        asyncAccessPolicy.inspectJoin(request, status -> {
            AsyncActorCallback.deliverDecision(
                    status,
                    this::recordAsyncJoinAccessDecision,
                    decision -> channel.tryJoinAfterAccessStatus(request, decision, callback),
                    () -> asyncAccessCallbackDeliveryFailures.incrementAndGet(),
                    () -> new ChatJoinResult(ChatJoinStatus.BACKPRESSURED,
                            request.channelId(), request.playerId(), 0),
                    callback
            );
        });
    }

    public void joinAfterAllianceChanged(
            AllianceMemberChangedEvent event,
            ChatJoinRequest request,
            Consumer<ChatJoinResult> callback
    ) {
        if (rejectJoinWhileDraining(request, callback)) {
            return;
        }
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        joinRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.channelIdentity(request.channelId()),
                ChatOperations.JOIN_CHANNEL);
        if (!admission.accepted()) {
            callback.accept(new ChatJoinResult(ChatJoinStatus.BACKPRESSURED, request.channelId(), request.playerId(), 0));
            return;
        }
        ChatChannelAgent channel = channel(request.channelId());
        if (!(accessPolicy instanceof EventAwareChatChannelAccessPolicy eventAware)) {
            channel.joinAfterAccessStatus(request, ChatJoinStatus.STALE_ALLIANCE, callback);
            return;
        }
        asyncAccessChecks.incrementAndGet();
        eventAware.inspectJoinAfterAllianceChanged(event, request, status -> {
            AsyncActorCallback.deliverDecision(
                    status,
                    this::recordAsyncJoinAccessDecision,
                    decision -> channel.tryJoinAfterAccessStatus(request, decision, callback),
                    () -> asyncAccessCallbackDeliveryFailures.incrementAndGet(),
                    () -> new ChatJoinResult(ChatJoinStatus.BACKPRESSURED,
                            request.channelId(), request.playerId(), 0),
                    callback
            );
        });
    }

    public void leave(ChatLeaveRequest request, Consumer<ChatLeaveResult> callback) {
        if (rejectLeaveWhileDraining(request, callback)) {
            return;
        }
        leaveRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.channelIdentity(request.channelId()),
                ChatOperations.LEAVE_CHANNEL);
        if (!admission.accepted()) {
            callback.accept(new ChatLeaveResult(ChatLeaveStatus.BACKPRESSURED, request.channelId(), request.playerId(), 0));
            return;
        }
        channel(request.channelId()).leave(request, callback);
    }

    public void send(ChatSendRequest request, Consumer<ChatSendResult> callback) {
        if (rejectSendWhileDraining(callback)) {
            return;
        }
        sendRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.channelIdentity(request.channelId()),
                ChatOperations.SEND_CHANNEL);
        if (!admission.accepted()) {
            callback.accept(ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED));
            return;
        }
        ChatChannelAgent channel = channel(request.channelId());
        if (asyncAccessPolicy != null) {
            asyncAccessChecks.incrementAndGet();
            asyncAccessPolicy.inspectSend(request, status -> {
                recordAsyncSendAccessDecision(status);
                if (asyncMessagePolicy == null) {
                    deliverAsyncAccessSendDecision(channel, request, status, callback);
                    return;
                }
                inspectAsyncMessage(
                        request,
                        decision -> channel.trySendAfterAccessAndMessageDecision(request, status, decision, callback),
                        callback
                );
            });
            return;
        }
        if (asyncMessagePolicy == null) {
            channel.send(request, callback);
            return;
        }
        inspectAsyncMessage(request,
                decision -> channel.trySendAfterMessageDecision(request, decision, callback),
                callback);
    }

    public void sendAfterAllianceChanged(
            AllianceMemberChangedEvent event,
            ChatSendRequest request,
            Consumer<ChatSendResult> callback
    ) {
        if (rejectSendWhileDraining(callback)) {
            return;
        }
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        sendRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.channelIdentity(request.channelId()),
                ChatOperations.SEND_CHANNEL);
        if (!admission.accepted()) {
            callback.accept(ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED));
            return;
        }
        ChatChannelAgent channel = channel(request.channelId());
        if (!(accessPolicy instanceof EventAwareChatChannelAccessPolicy eventAware)) {
            channel.sendAfterAccessStatus(request, ChatSendStatus.STALE_ALLIANCE, callback);
            return;
        }
        asyncAccessChecks.incrementAndGet();
        eventAware.inspectSendAfterAllianceChanged(event, request, status -> {
            recordAsyncSendAccessDecision(status);
            if (asyncMessagePolicy == null) {
                deliverAsyncAccessSendDecision(channel, request, status, callback);
                return;
            }
            inspectAsyncMessage(
                    request,
                    decision -> channel.trySendAfterAccessAndMessageDecision(request, status, decision, callback),
                    callback
            );
        });
    }

    public void removeAllianceMember(long allianceId, long playerId) {
        ChatChannelAgent channel = channels.get(ChatChannelIds.alliance(allianceId));
        if (channel != null) {
            channel.removeMemberDueToAllianceChange(playerId);
        }
    }

    public void retainAllianceMembers(long allianceId, Set<Long> memberIds) {
        Objects.requireNonNull(memberIds, "memberIds");
        ChatChannelAgent channel = channels.get(ChatChannelIds.alliance(allianceId));
        if (channel != null) {
            channel.retainMembersDueToAllianceSnapshot(memberIds);
        }
    }

    public ChatServiceStats stats() {
        long retainedMessages = 0;
        long droppedHistoryMessages = 0;
        long allianceEventRemovedMembers = 0;
        long allianceSnapshotRemovedMembers = 0;
        ChatDeliveryResult deliveryStats = ChatDeliveryResult.empty();
        ChatPersistenceStats persistenceStats = ChatPersistenceStats.empty();
        for (ChatChannelAgent channel : channels.values()) {
            retainedMessages += channel.retainedMessages();
            droppedHistoryMessages += channel.droppedHistoryMessages();
            allianceEventRemovedMembers += channel.allianceEventRemovedMembers();
            allianceSnapshotRemovedMembers += channel.allianceSnapshotRemovedMembers();
            deliveryStats = deliveryStats.plus(channel.deliveryStats());
            persistenceStats = persistenceStats.plus(channel.persistenceStats());
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
                deliveryStats.failedRecipients(),
                allianceEventRemovedMembers,
                allianceSnapshotRemovedMembers
        ).withAsyncMessageStats(
                asyncMessageChecks.get(),
                asyncMessageSentDecisions.get(),
                asyncMessageRejectedDecisions.get(),
                asyncMessageBackpressuredDecisions.get(),
                asyncMessageCallbackDeliveryFailures.get()
        ).withAsyncAccessStats(
                asyncAccessChecks.get(),
                asyncAccessAllowedDecisions.get(),
                asyncAccessRejectedDecisions.get(),
                asyncAccessBackpressuredDecisions.get(),
                asyncAccessCallbackDeliveryFailures.get()
        ).withPersistenceStats(persistenceStats);
    }

    public void beginDrain() {
        draining.set(true);
    }

    public void resumeAccepting() {
        draining.set(false);
    }

    public boolean isDraining() {
        return draining.get();
    }

    ChatChannelAgent channel(String channelId) {
        String normalized = ChatJoinRequest.normalizeChannelId(channelId);
        return channels.computeIfAbsent(normalized, this::createChannel);
    }

    private ChatChannelAgent createChannel(String channelId) {
        var identity = ChatActorIds.channelIdentity(channelId);
        ActorRef actor = lifecycles == null
                ? actors.actor(ChatActorIds.channelActorId(channelId))
                : lifecycles.activate(
                        identity, ChatActorIds.channelActorId(channelId)
                ).actorRef();
        ChatChannelAgent agent = new ChatChannelAgent(
                messages,
                actor,
                channelId,
                interests,
                accessPolicy,
                messagePolicy,
                deliverySink,
                clock,
                maxHistoryMessages
        );
        if (stateStore != null) {
            long ownerToken = lifecycles == null
                    ? 0
                    : lifecycles.lease(identity).map(lease -> lease.fencingToken()).orElse(0L);
            agent.attachPersistence(new ChatAgentPersistence(
                    identity,
                    actor,
                    messages,
                    stateStore,
                    ownerToken,
                    persistencePendingCapacity,
                    agent::snapshotNow,
                    agent::restoreSnapshot,
                    () -> fence(identity, actor)
            ));
        }
        return agent;
    }

    private void fence(com.commonbattle.actor.agent.AgentIdentity identity, ActorRef actor) {
        if (lifecycles == null) {
            actors.retireCurrent(actor);
            return;
        }
        lifecycles.fenceCurrent(identity, actor);
    }

    private boolean rejectJoinWhileDraining(ChatJoinRequest request, Consumer<ChatJoinResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        if (!draining.get()) {
            return false;
        }
        callback.accept(new ChatJoinResult(
                ChatJoinStatus.BACKPRESSURED, request.channelId(), request.playerId(), 0
        ));
        return true;
    }

    private boolean rejectLeaveWhileDraining(ChatLeaveRequest request, Consumer<ChatLeaveResult> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        if (!draining.get()) {
            return false;
        }
        callback.accept(new ChatLeaveResult(
                ChatLeaveStatus.BACKPRESSURED, request.channelId(), request.playerId(), 0
        ));
        return true;
    }

    private boolean rejectSendWhileDraining(Consumer<ChatSendResult> callback) {
        Objects.requireNonNull(callback, "callback");
        if (!draining.get()) {
            return false;
        }
        callback.accept(ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED));
        return true;
    }

    private void inspectAsyncMessage(
            ChatSendRequest request,
            java.util.function.Function<ChatMessageDecision, AgentDeliveryResult> delivery,
            Consumer<ChatSendResult> callback
    ) {
        asyncMessageChecks.incrementAndGet();
        asyncMessagePolicy.inspect(request, decision -> {
            AsyncActorCallback.deliverDecision(
                    decision,
                    this::recordAsyncDecision,
                    delivery,
                    () -> asyncMessageCallbackDeliveryFailures.incrementAndGet(),
                    () -> ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED),
                    callback
            );
        });
    }

    private void deliverAsyncAccessSendDecision(
            ChatChannelAgent channel,
            ChatSendRequest request,
            ChatSendStatus status,
            Consumer<ChatSendResult> callback
    ) {
        AsyncActorCallback.deliverDecision(
                status,
                ignored -> {
                },
                decision -> channel.trySendAfterAccessStatus(request, decision, callback),
                () -> asyncAccessCallbackDeliveryFailures.incrementAndGet(),
                () -> ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED),
                callback
        );
    }

    private void recordAsyncDecision(ChatMessageDecision decision) {
        if (decision.status() == ChatSendStatus.SENT) {
            asyncMessageSentDecisions.incrementAndGet();
            return;
        }
        asyncMessageRejectedDecisions.incrementAndGet();
        if (decision.status() == ChatSendStatus.BACKPRESSURED) {
            asyncMessageBackpressuredDecisions.incrementAndGet();
        }
    }

    private void recordAsyncJoinAccessDecision(ChatJoinStatus status) {
        if (status == ChatJoinStatus.JOINED) {
            asyncAccessAllowedDecisions.incrementAndGet();
            return;
        }
        asyncAccessRejectedDecisions.incrementAndGet();
        if (status == ChatJoinStatus.BACKPRESSURED) {
            asyncAccessBackpressuredDecisions.incrementAndGet();
        }
    }

    private void recordAsyncSendAccessDecision(ChatSendStatus status) {
        if (status == ChatSendStatus.SENT) {
            asyncAccessAllowedDecisions.incrementAndGet();
            return;
        }
        asyncAccessRejectedDecisions.incrementAndGet();
        if (status == ChatSendStatus.BACKPRESSURED) {
            asyncAccessBackpressuredDecisions.incrementAndGet();
        }
    }
}
