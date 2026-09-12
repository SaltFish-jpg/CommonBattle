package com.commonbattle.game.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界内存投递队列。
 * 该实现用于本地样例和测试；生产环境可替换为 Gateway 会话投递、跨进程推送或离线消息队列。
 */
public final class BoundedInMemoryChatDeliverySink implements ChatDeliverySink {
    private final int maxPendingPerRecipient;
    private final ChatDeliveryOverflowStrategy overflowStrategy;
    private final Map<Long, ArrayDeque<ChatDelivery>> pending = new ConcurrentHashMap<>();
    private final AtomicLong acceptedRecipients = new AtomicLong();
    private final AtomicLong droppedRecipients = new AtomicLong();
    private final AtomicLong failedRecipients = new AtomicLong();

    public BoundedInMemoryChatDeliverySink(int maxPendingPerRecipient, ChatDeliveryOverflowStrategy overflowStrategy) {
        if (maxPendingPerRecipient <= 0) {
            throw new IllegalArgumentException("maxPendingPerRecipient must be positive");
        }
        this.maxPendingPerRecipient = maxPendingPerRecipient;
        this.overflowStrategy = Objects.requireNonNull(overflowStrategy, "overflowStrategy");
    }

    @Override
    public ChatDeliveryResult deliver(ChatDeliveryEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        long accepted = 0;
        long dropped = 0;
        for (long recipient : envelope.recipients()) {
            DeliveryOfferResult result = offer(recipient, envelope.delivery());
            if (result.accepted()) {
                accepted++;
            }
            if (result.dropped()) {
                dropped++;
            }
        }
        acceptedRecipients.addAndGet(accepted);
        droppedRecipients.addAndGet(dropped);
        return new ChatDeliveryResult(accepted, dropped, 0);
    }

    public List<ChatDelivery> pending(long playerId) {
        ArrayDeque<ChatDelivery> deliveries = pending.get(playerId);
        if (deliveries == null) {
            return List.of();
        }
        synchronized (deliveries) {
            return List.copyOf(deliveries);
        }
    }

    public List<ChatDelivery> drain(long playerId, int maxCount) {
        if (maxCount <= 0) {
            throw new IllegalArgumentException("maxCount must be positive");
        }
        ArrayDeque<ChatDelivery> deliveries = pending.get(playerId);
        if (deliveries == null) {
            return List.of();
        }
        List<ChatDelivery> drained = new ArrayList<>();
        synchronized (deliveries) {
            while (!deliveries.isEmpty() && drained.size() < maxCount) {
                drained.add(deliveries.removeFirst());
            }
        }
        return List.copyOf(drained);
    }

    public ChatDeliveryResult stats() {
        return new ChatDeliveryResult(
                acceptedRecipients.get(),
                droppedRecipients.get(),
                failedRecipients.get()
        );
    }

    private DeliveryOfferResult offer(long recipient, ChatDelivery delivery) {
        ArrayDeque<ChatDelivery> deliveries = pending.computeIfAbsent(recipient, ignored -> new ArrayDeque<>());
        synchronized (deliveries) {
            if (deliveries.size() < maxPendingPerRecipient) {
                deliveries.addLast(delivery);
                return new DeliveryOfferResult(true, false);
            }
            if (overflowStrategy == ChatDeliveryOverflowStrategy.DROP_NEWEST) {
                return new DeliveryOfferResult(false, true);
            }
            deliveries.removeFirst();
            deliveries.addLast(delivery);
            return new DeliveryOfferResult(true, true);
        }
    }

    private record DeliveryOfferResult(boolean accepted, boolean dropped) {
    }
}
