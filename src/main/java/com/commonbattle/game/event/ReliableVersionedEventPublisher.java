package com.commonbattle.game.event;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 通用可靠版本事件发布器。
 * 状态 owner 先写 outbox；只有下游明确确认后才删除记录，失败则按策略退避并最终隔离到死信队列。
 */
public final class ReliableVersionedEventPublisher implements EventPublisher, EventOutboxDeadLetterAdmin {
    private final VersionedEventOutbox outbox;
    private final ConfirmableEventPublisher delegate;
    private final VersionedEventRetryPolicy retryPolicy;
    private final Clock clock;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final LongAdder appended = new LongAdder();
    private final LongAdder dispatchAttempts = new LongAdder();
    private final LongAdder confirmed = new LongAdder();
    private final LongAdder failedAttempts = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();

    public ReliableVersionedEventPublisher(VersionedEventOutbox outbox, EventPublisher delegate) {
        this(outbox, delegate, VersionedEventRetryPolicy.defaults(), Clock.systemUTC());
    }

    public ReliableVersionedEventPublisher(
            VersionedEventOutbox outbox,
            EventPublisher delegate,
            VersionedEventRetryPolicy retryPolicy,
            Clock clock
    ) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.delegate = confirmable(Objects.requireNonNull(delegate, "delegate"));
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void publish(VersionedEvent event) {
        PendingVersionedEvent entry = outbox.append(Objects.requireNonNull(event, "event"));
        appended.increment();
        dispatch(entry);
    }

    public void replayPending() {
        outbox.ready(clock.instant(), retryPolicy.replayBatchSize()).forEach(this::dispatch);
    }

    /**
     * 把指定死信恢复为待发送事件并立即尝试投递。
     * 返回 false 表示该 outboxId 不存在或当前不是死信。
     */
    public boolean redriveDeadLetter(long outboxId) {
        Instant now = clock.instant();
        if (!outbox.redriveDeadLetter(outboxId, now)) {
            return false;
        }
        outbox.ready(now, retryPolicy.replayBatchSize()).stream()
                .filter(entry -> entry.id() == outboxId)
                .findFirst()
                .ifPresent(this::dispatch);
        return true;
    }

    @Override
    public String target() {
        return "event-outbox";
    }

    @Override
    public java.util.List<DeadLetteredVersionedEvent> outboxDeadLetters() {
        return outbox.deadLetters();
    }

    @Override
    public boolean redriveOutboxDeadLetter(long outboxId) {
        return redriveDeadLetter(outboxId);
    }

    public ReliableVersionedEventPublisherStats stats() {
        return new ReliableVersionedEventPublisherStats(
                appended.sum(),
                dispatchAttempts.sum(),
                confirmed.sum(),
                failedAttempts.sum(),
                deadLettered.sum(),
                inFlight.size()
        );
    }

    private void dispatch(PendingVersionedEvent entry) {
        if (!inFlight.add(entry.id())) {
            return;
        }
        dispatchAttempts.increment();
        AtomicBoolean completed = new AtomicBoolean();
        try {
            delegate.publish(entry.event(), new EventPublishCallback() {
                @Override
                public void success() {
                    if (!completed.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        outbox.markPublished(entry.id());
                        confirmed.increment();
                    } finally {
                        inFlight.remove(entry.id());
                    }
                }

                @Override
                public void failure(Throwable error) {
                    completeFailure(entry, error, completed);
                }
            });
        } catch (RuntimeException | Error error) {
            completeFailure(entry, error, completed);
        }
    }

    private void completeFailure(PendingVersionedEvent entry, Throwable error, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            failedAttempts.increment();
            Instant failedAt = clock.instant();
            int attempts = entry.attempts() + 1;
            Instant nextAttemptAt = failedAt.plus(retryPolicy.backoffAfterFailure(attempts));
            OutboxFailureResult result = outbox.markAttemptFailed(
                    entry.id(),
                    error == null ? new IllegalStateException("event publish failed without cause") : error,
                    failedAt,
                    nextAttemptAt,
                    retryPolicy.maxAttempts()
            );
            if (result == OutboxFailureResult.DEAD_LETTERED) {
                deadLettered.increment();
            }
        } finally {
            inFlight.remove(entry.id());
        }
    }

    private static ConfirmableEventPublisher confirmable(EventPublisher delegate) {
        if (delegate instanceof ConfirmableEventPublisher confirmable) {
            return confirmable;
        }
        return (event, callback) -> {
            try {
                delegate.publish(event);
                callback.success();
            } catch (RuntimeException | Error error) {
                callback.failure(error);
            }
        };
    }
}
