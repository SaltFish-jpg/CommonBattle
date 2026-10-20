package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.backpressure.InboundAdmissionController;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.agent.AsyncActorCallback;
import com.commonbattle.game.social.FriendChangedEvent;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 私聊会话管理器。
 * 只定位会话 Actor，不直接保存私聊业务状态。
 */
public final class DirectChatSessionManager implements ChatRuntimeView {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final DirectChatAccessPolicy accessPolicy;
    private final AsyncDirectChatAccessPolicy asyncAccessPolicy;
    private final ChatMessagePolicy messagePolicy;
    private final ChatDeliverySink deliverySink;
    private final InboundAdmissionController admissions;
    private final Clock clock;
    private final int maxHistoryMessages;
    private final AgentLifecycleManager lifecycles;
    private final AsyncChatStateStore stateStore;
    private final int persistencePendingCapacity;
    private final ConcurrentMap<String, DirectChatSessionAgent> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sendRequests = new AtomicLong();
    private final AtomicLong asyncAccessChecks = new AtomicLong();
    private final AtomicLong asyncAccessAllowedDecisions = new AtomicLong();
    private final AtomicLong asyncAccessRejectedDecisions = new AtomicLong();
    private final AtomicLong asyncAccessBackpressuredDecisions = new AtomicLong();
    private final AtomicLong asyncAccessCallbackDeliveryFailures = new AtomicLong();
    private final AtomicBoolean draining = new AtomicBoolean();

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ChatMessagePolicy messagePolicy,
            Clock clock
    ) {
        this(actors, messages, DirectChatAccessPolicy.allowAll(), messagePolicy, ChatDeliverySink.noop(), clock,
                ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES, (target, operation) -> AdmissionDecision.accept());
    }

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ChatMessagePolicy messagePolicy,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(actors, messages, DirectChatAccessPolicy.allowAll(), messagePolicy, ChatDeliverySink.noop(), clock,
                maxHistoryMessages, (target, operation) -> AdmissionDecision.accept());
    }

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages
    ) {
        this(actors, messages, DirectChatAccessPolicy.allowAll(), messagePolicy, deliverySink, clock, maxHistoryMessages,
                (target, operation) -> AdmissionDecision.accept());
    }

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions
    ) {
        this(actors, messages, DirectChatAccessPolicy.allowAll(), messagePolicy, deliverySink, clock,
                maxHistoryMessages, admissions);
    }

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            DirectChatAccessPolicy accessPolicy,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions
    ) {
        this(actors, messages, accessPolicy, null, messagePolicy, deliverySink, clock, maxHistoryMessages, admissions);
    }

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            DirectChatAccessPolicy accessPolicy,
            AsyncDirectChatAccessPolicy asyncAccessPolicy,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions
    ) {
        this(
                actors, messages, accessPolicy, asyncAccessPolicy, messagePolicy, deliverySink, clock,
                maxHistoryMessages, admissions, null
        );
    }

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            DirectChatAccessPolicy accessPolicy,
            AsyncDirectChatAccessPolicy asyncAccessPolicy,
            ChatMessagePolicy messagePolicy,
            ChatDeliverySink deliverySink,
            Clock clock,
            int maxHistoryMessages,
            InboundAdmissionController admissions,
            AgentLifecycleManager lifecycles
    ) {
        this(
                actors, messages, accessPolicy, asyncAccessPolicy, messagePolicy, deliverySink, clock,
                maxHistoryMessages, admissions, lifecycles, null, 256
        );
    }

    public DirectChatSessionManager(
            ActorSystem actors,
            AgentMessagePort messages,
            DirectChatAccessPolicy accessPolicy,
            AsyncDirectChatAccessPolicy asyncAccessPolicy,
            ChatMessagePolicy messagePolicy,
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
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.asyncAccessPolicy = asyncAccessPolicy;
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
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
                if (ChatActorIds.DIRECT_TYPE.equals(record.identity().type())) {
                    sessions.remove(record.identity().key());
                }
            });
        }
    }

    public void send(DirectChatSendRequest request, Consumer<ChatSendResult> callback) {
        if (rejectSendWhileDraining(callback)) {
            return;
        }
        sendRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.directIdentity(request.senderId(), request.receiverId()),
                ChatOperations.SEND_DIRECT);
        if (!admission.accepted()) {
            callback.accept(ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED));
            return;
        }
        DirectChatSessionAgent session = session(request.senderId(), request.receiverId());
        if (asyncAccessPolicy == null) {
            session.send(request, callback);
            return;
        }
        asyncAccessChecks.incrementAndGet();
        asyncAccessPolicy.inspect(request, status -> {
            deliverAsyncAccessDecision(session, request, status, callback);
        });
    }

    public void sendAfterFriendChanged(
            FriendChangedEvent event,
            DirectChatSendRequest request,
            Consumer<ChatSendResult> callback
    ) {
        if (rejectSendWhileDraining(callback)) {
            return;
        }
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        sendRequests.incrementAndGet();
        AdmissionDecision admission = admissions.admit(ChatActorIds.directIdentity(request.senderId(), request.receiverId()),
                ChatOperations.SEND_DIRECT);
        if (!admission.accepted()) {
            callback.accept(ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED));
            return;
        }
        DirectChatSessionAgent session = session(request.senderId(), request.receiverId());
        if (!(accessPolicy instanceof EventAwareDirectChatAccessPolicy eventAware)) {
            session.sendAfterAccessStatus(request, ChatSendStatus.STALE_FRIENDS, callback);
            return;
        }
        asyncAccessChecks.incrementAndGet();
        eventAware.inspectAfterFriendChanged(event, request, status -> {
            deliverAsyncAccessDecision(session, request, status, callback);
        });
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
        ChatPersistenceStats persistenceStats = ChatPersistenceStats.empty();
        for (DirectChatSessionAgent session : sessions.values()) {
            retainedMessages += session.retainedMessages();
            droppedHistoryMessages += session.droppedHistoryMessages();
            deliveryStats = deliveryStats.plus(session.deliveryStats());
            persistenceStats = persistenceStats.plus(session.persistenceStats());
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
        ).withAsyncAccessStats(
                asyncAccessChecks.get(),
                asyncAccessAllowedDecisions.get(),
                asyncAccessRejectedDecisions.get(),
                asyncAccessBackpressuredDecisions.get(),
                asyncAccessCallbackDeliveryFailures.get()
        ).withPersistenceStats(persistenceStats);
    }

    DirectChatSessionAgent session(long firstPlayerId, long secondPlayerId) {
        String sessionId = ChatChannelIds.direct(firstPlayerId, secondPlayerId);
        return sessions.computeIfAbsent(sessionId, ignored -> create(firstPlayerId, secondPlayerId));
    }

    private DirectChatSessionAgent create(long firstPlayerId, long secondPlayerId) {
        String sessionId = ChatChannelIds.direct(firstPlayerId, secondPlayerId);
        var identity = ChatActorIds.directIdentity(firstPlayerId, secondPlayerId);
        ActorRef actor = lifecycles == null
                ? actors.actor(ChatActorIds.directActorId(sessionId))
                : lifecycles.activate(
                        identity,
                        ChatActorIds.directActorId(sessionId)
                ).actorRef();
        DirectChatSessionAgent agent = new DirectChatSessionAgent(
                messages,
                actor,
                firstPlayerId,
                secondPlayerId,
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

    public void beginDrain() {
        draining.set(true);
    }

    public void resumeAccepting() {
        draining.set(false);
    }

    public boolean isDraining() {
        return draining.get();
    }

    private void fence(com.commonbattle.actor.agent.AgentIdentity identity, ActorRef actor) {
        if (lifecycles == null) {
            actors.retireCurrent(actor);
            return;
        }
        lifecycles.fenceCurrent(identity, actor);
    }

    private boolean rejectSendWhileDraining(Consumer<ChatSendResult> callback) {
        Objects.requireNonNull(callback, "callback");
        if (!draining.get()) {
            return false;
        }
        callback.accept(ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED));
        return true;
    }

    private void recordAsyncAccessDecision(ChatSendStatus status) {
        if (status == ChatSendStatus.SENT) {
            asyncAccessAllowedDecisions.incrementAndGet();
            return;
        }
        asyncAccessRejectedDecisions.incrementAndGet();
        if (status == ChatSendStatus.BACKPRESSURED) {
            asyncAccessBackpressuredDecisions.incrementAndGet();
        }
    }

    private void deliverAsyncAccessDecision(
            DirectChatSessionAgent session,
            DirectChatSendRequest request,
            ChatSendStatus status,
            Consumer<ChatSendResult> callback
    ) {
        AsyncActorCallback.deliverDecision(
                status,
                this::recordAsyncAccessDecision,
                decision -> session.trySendAfterAccessStatus(request, decision, callback),
                () -> asyncAccessCallbackDeliveryFailures.incrementAndGet(),
                () -> ChatSendResult.rejected(ChatSendStatus.BACKPRESSURED),
                callback
        );
    }
}
