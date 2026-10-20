package com.commonbattle.game.event;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.agent.AsyncActorCallback;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 把版本事件订阅回调转换为 Actor 邮箱任务。
 * 它用于 Scene、Chat、排行榜等非 owner 服务，保证事件中心或 Netty 回调线程不直接执行业务逻辑。
 */
public final class ActorMailboxEventSubscriber
        implements VersionedEventSubscriber, ActorEventSubscriberView, EventInboxDeadLetterAdmin {
    private final AgentMessagePort messages;
    private final ActorRef target;
    private final ActorTaskCategory category;
    private final ActorEventHandler handler;
    private final String inboxTopic;
    private final VersionedEventInbox inbox;
    private final EventInboxRetryPolicy retryPolicy;
    private final Clock clock;
    private final Set<OwnerStream> inFlightStreams = ConcurrentHashMap.newKeySet();
    private volatile Consumer<VersionedEvent> rejectedEventHandler = ignored -> {
    };
    private final AtomicLong receivedEvents = new AtomicLong();
    private final AtomicLong enqueuedEvents = new AtomicLong();
    private final AtomicLong rejectedEvents = new AtomicLong();
    private final AtomicLong handledEvents = new AtomicLong();
    private final AtomicLong failedEvents = new AtomicLong();
    private final AtomicLong duplicateEvents = new AtomicLong();
    private final AtomicLong retryScheduledEvents = new AtomicLong();
    private final AtomicLong deadLetteredEvents = new AtomicLong();

    public ActorMailboxEventSubscriber(AgentMessagePort messages, ActorRef target, ActorEventHandler handler) {
        this(messages, target, ActorTaskCategory.EVENT, handler);
    }

    public ActorMailboxEventSubscriber(
            AgentMessagePort messages,
            ActorRef target,
            ActorTaskCategory category,
            ActorEventHandler handler
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.target = Objects.requireNonNull(target, "target");
        this.category = Objects.requireNonNull(category, "category");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.inboxTopic = "";
        this.inbox = null;
        this.retryPolicy = null;
        this.clock = null;
    }

    public ActorMailboxEventSubscriber(
            AgentMessagePort messages,
            ActorRef target,
            ActorTaskCategory category,
            ActorEventHandler handler,
            String topic,
            VersionedEventInbox inbox,
            EventInboxRetryPolicy retryPolicy,
            Clock clock
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.target = Objects.requireNonNull(target, "target");
        this.category = Objects.requireNonNull(category, "category");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.inboxTopic = Objects.requireNonNull(topic, "topic");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ActorMailboxEventSubscriber(
            AgentMessagePort messages,
            ActorRef target,
            ActorEventHandler handler,
            String topic,
            VersionedEventInbox inbox,
            EventInboxRetryPolicy retryPolicy,
            Clock clock
    ) {
        this(messages, target, ActorTaskCategory.EVENT, handler, topic, inbox, retryPolicy, clock);
    }

    /**
     * 绑定邮箱拒绝后的修复入口。
     * 常见用途是把 ownerKey 交给快照修复调度器，避免事件被 mailbox 背压丢弃后投影长期停在旧版本。
     */
    public void onRejectedEvent(Consumer<VersionedEvent> handler) {
        this.rejectedEventHandler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void onEvent(VersionedEvent event) {
        Objects.requireNonNull(event, "event");
        receivedEvents.incrementAndGet();
        if (inbox != null) {
            EventInboxAppendStatus status = inbox.appendIfAbsent(event, clock.instant());
            if (status != EventInboxAppendStatus.APPENDED) {
                duplicateEvents.incrementAndGet();
            }
            if (status != EventInboxAppendStatus.DEAD_LETTERED) {
                dispatchNext(event.topic(), event.ownerKey());
            }
            return;
        }
        AgentDeliveryResult result = AsyncActorCallback.deliverToActor(
                event,
                ignored -> messages.tryTellLocal(target, category, context -> handleInMailbox(context, event)),
                ignored -> handleRejected(event)
        );
        if (result.accepted()) {
            enqueuedEvents.incrementAndGet();
        }
    }

    /**
     * 扫描当前 topic 到期的 inbox 记录，并重新投递到 Actor 邮箱。
     */
    public void replayPending() {
        if (inbox == null) {
            return;
        }
        inbox.ready(topic(), clock.instant(), retryPolicy.replayBatchSize()).forEach(entry ->
                dispatchNext(entry.key().topic(), entry.key().ownerKey()));
    }

    /**
     * 恢复一条死信并立即尝试按 owner 顺序投递。
     */
    public boolean redriveDeadLetter(EventInboxKey key) {
        if (inbox == null || !inbox.redriveDeadLetter(key, clock.instant())) {
            return false;
        }
        dispatchNext(key.topic(), key.ownerKey());
        return true;
    }

    @Override
    public String target() {
        return target.id();
    }

    @Override
    public String topic() {
        return inboxTopic;
    }

    @Override
    public java.util.List<DeadLetteredEventInboxEntry> inboxDeadLetters() {
        return inbox == null ? java.util.List.of() : inbox.deadLetters(inboxTopic);
    }

    @Override
    public boolean redriveInboxDeadLetter(EventInboxKey key) {
        return redriveDeadLetter(key);
    }

    private void handleInMailbox(com.commonbattle.actor.ActorContext context, VersionedEvent event) {
        try {
            handler.handle(context, event);
            handledEvents.incrementAndGet();
        } catch (RuntimeException | Error e) {
            failedEvents.incrementAndGet();
            throw e;
        }
    }

    private void handleDurableInMailbox(
            com.commonbattle.actor.ActorContext context,
            PendingEventInboxEntry entry,
            OwnerStream stream
    ) {
        boolean handled = false;
        try {
            handler.handle(context, entry.event());
            inbox.markHandled(entry.key());
            handledEvents.incrementAndGet();
            handled = true;
        } catch (RuntimeException | Error error) {
            failedEvents.incrementAndGet();
            EventInboxFailureResult result = recordFailure(entry, error);
            if (result == EventInboxFailureResult.DEAD_LETTERED) {
                notifyRepair(entry.event(), error);
            }
            throw error;
        } finally {
            inFlightStreams.remove(stream);
            if (handled) {
                dispatchNext(stream.topic(), stream.ownerKey());
            }
        }
    }

    private void dispatchNext(String topic, String ownerKey) {
        OwnerStream stream = new OwnerStream(topic, ownerKey);
        if (!inFlightStreams.add(stream)) {
            return;
        }
        PendingEventInboxEntry entry = inbox.nextReady(topic, ownerKey, clock.instant()).orElse(null);
        if (entry == null) {
            inFlightStreams.remove(stream);
            return;
        }
        AgentDeliveryResult result;
        try {
            result = messages.tryTellLocal(
                    target,
                    category,
                    context -> handleDurableInMailbox(context, entry, stream)
            );
        } catch (RuntimeException | Error error) {
            failedEvents.incrementAndGet();
            try {
                recordFailure(entry, error);
            } finally {
                inFlightStreams.remove(stream);
            }
            throw error;
        }
        if (result.accepted()) {
            enqueuedEvents.incrementAndGet();
            return;
        }
        rejectedEvents.incrementAndGet();
        try {
            recordFailure(entry, new IllegalStateException(
                    "Actor event delivery rejected: " + result.status() + " " + result.reason()
            ));
            rejectedEventHandler.accept(entry.event());
        } catch (RuntimeException | Error error) {
            failedEvents.incrementAndGet();
            throw error;
        } finally {
            inFlightStreams.remove(stream);
        }
    }

    private EventInboxFailureResult recordFailure(PendingEventInboxEntry entry, Throwable error) {
        Instant failedAt = clock.instant();
        int attempts = entry.attempts() + 1;
        EventInboxFailureResult result = inbox.markAttemptFailed(
                entry.key(),
                error,
                failedAt,
                failedAt.plus(retryPolicy.backoffAfterFailure(attempts)),
                retryPolicy.maxAttempts()
        );
        if (result == EventInboxFailureResult.RETRY_SCHEDULED) {
            retryScheduledEvents.incrementAndGet();
        } else if (result == EventInboxFailureResult.DEAD_LETTERED) {
            deadLetteredEvents.incrementAndGet();
        }
        return result;
    }

    private void notifyRepair(VersionedEvent event, Throwable originalFailure) {
        try {
            rejectedEventHandler.accept(event);
        } catch (RuntimeException | Error repairFailure) {
            failedEvents.incrementAndGet();
            originalFailure.addSuppressed(repairFailure);
        }
    }

    private void handleRejected(VersionedEvent event) {
        rejectedEvents.incrementAndGet();
        try {
            rejectedEventHandler.accept(event);
        } catch (RuntimeException | Error e) {
            failedEvents.incrementAndGet();
            throw e;
        }
    }

    @Override
    public ActorEventSubscriberStats stats() {
        int pendingInboxEvents = inbox == null ? 0 : inbox.pending(topic()).size();
        int inboxDeadLetters = inbox == null ? 0 : inbox.deadLetters(topic()).size();
        return new ActorEventSubscriberStats(
                receivedEvents.get(),
                enqueuedEvents.get(),
                rejectedEvents.get(),
                handledEvents.get(),
                failedEvents.get(),
                duplicateEvents.get(),
                retryScheduledEvents.get(),
                deadLetteredEvents.get(),
                pendingInboxEvents,
                inboxDeadLetters
        );
    }

    private record OwnerStream(String topic, String ownerKey) {
    }
}
