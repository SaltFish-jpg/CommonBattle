package com.commonbattle.actor;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * ActorSystem 运行时配置。
 * 用于控制批处理公平性、单 Actor 邮箱容量和关闭等待时间。
 */
public record ActorSystemConfig(
        int workerThreads,
        int batchSize,
        int mailboxCapacity,
        ActorOverflowStrategy overflowStrategy,
        Duration shutdownTimeout,
        Map<ActorTaskCategory, Integer> categoryCapacities,
        Duration slowTaskThreshold
) {
    public static final int DEFAULT_BATCH_SIZE = 64;
    public static final int DEFAULT_MAILBOX_CAPACITY = 4096;

    public ActorSystemConfig(
            int workerThreads,
            int batchSize,
            int mailboxCapacity,
            ActorOverflowStrategy overflowStrategy,
            Duration shutdownTimeout
    ) {
        this(workerThreads, batchSize, mailboxCapacity, overflowStrategy, shutdownTimeout, Map.of(), Duration.ZERO);
    }

    public ActorSystemConfig(
            int workerThreads,
            int batchSize,
            int mailboxCapacity,
            ActorOverflowStrategy overflowStrategy,
            Duration shutdownTimeout,
            Map<ActorTaskCategory, Integer> categoryCapacities
    ) {
        this(workerThreads, batchSize, mailboxCapacity, overflowStrategy, shutdownTimeout, categoryCapacities, Duration.ZERO);
    }

    public ActorSystemConfig {
        if (workerThreads <= 0) {
            throw new IllegalArgumentException("workerThreads must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (mailboxCapacity <= 0) {
            throw new IllegalArgumentException("mailboxCapacity must be positive");
        }
        if (overflowStrategy == null) {
            throw new IllegalArgumentException("overflowStrategy must not be null");
        }
        if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must not be negative");
        }
        if (categoryCapacities == null) {
            throw new IllegalArgumentException("categoryCapacities must not be null");
        }
        if (slowTaskThreshold == null || slowTaskThreshold.isNegative()) {
            throw new IllegalArgumentException("slowTaskThreshold must not be negative");
        }
        EnumMap<ActorTaskCategory, Integer> normalized = new EnumMap<>(ActorTaskCategory.class);
        for (Map.Entry<ActorTaskCategory, Integer> entry : categoryCapacities.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("categoryCapacities key must not be null");
            }
            if (entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException("category capacity must be positive");
            }
            normalized.put(entry.getKey(), entry.getValue());
        }
        categoryCapacities = Map.copyOf(normalized);
    }

    public static ActorSystemConfig defaults(int workerThreads) {
        return new ActorSystemConfig(
                workerThreads,
                DEFAULT_BATCH_SIZE,
                DEFAULT_MAILBOX_CAPACITY,
                ActorOverflowStrategy.REJECT,
                Duration.ofSeconds(3)
        );
    }

    public ActorSystemConfig withBatchSize(int value) {
        return new ActorSystemConfig(workerThreads, value, mailboxCapacity, overflowStrategy, shutdownTimeout,
                categoryCapacities, slowTaskThreshold);
    }

    public ActorSystemConfig withMailboxCapacity(int value) {
        return new ActorSystemConfig(workerThreads, batchSize, value, overflowStrategy, shutdownTimeout,
                categoryCapacities, slowTaskThreshold);
    }

    public ActorSystemConfig withOverflowStrategy(ActorOverflowStrategy value) {
        return new ActorSystemConfig(workerThreads, batchSize, mailboxCapacity, value, shutdownTimeout,
                categoryCapacities, slowTaskThreshold);
    }

    public ActorSystemConfig withCategoryCapacity(ActorTaskCategory category, int capacity) {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        EnumMap<ActorTaskCategory, Integer> next = new EnumMap<>(ActorTaskCategory.class);
        next.putAll(categoryCapacities);
        next.put(category, capacity);
        return new ActorSystemConfig(workerThreads, batchSize, mailboxCapacity, overflowStrategy, shutdownTimeout, next,
                slowTaskThreshold);
    }

    public ActorSystemConfig withSlowTaskThreshold(Duration value) {
        return new ActorSystemConfig(workerThreads, batchSize, mailboxCapacity, overflowStrategy, shutdownTimeout,
                categoryCapacities, value);
    }

    int capacityFor(ActorTaskCategory category) {
        return categoryCapacities.getOrDefault(category, mailboxCapacity);
    }
}
