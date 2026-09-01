package com.commonbattle.actor;

import java.time.Duration;

/**
 * ActorSystem 运行时配置。
 * 用于控制批处理公平性、单 Actor 邮箱容量和关闭等待时间。
 */
public record ActorSystemConfig(
        int workerThreads,
        int batchSize,
        int mailboxCapacity,
        ActorOverflowStrategy overflowStrategy,
        Duration shutdownTimeout
) {
    public static final int DEFAULT_BATCH_SIZE = 64;
    public static final int DEFAULT_MAILBOX_CAPACITY = 4096;

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
        return new ActorSystemConfig(workerThreads, value, mailboxCapacity, overflowStrategy, shutdownTimeout);
    }

    public ActorSystemConfig withMailboxCapacity(int value) {
        return new ActorSystemConfig(workerThreads, batchSize, value, overflowStrategy, shutdownTimeout);
    }
}
