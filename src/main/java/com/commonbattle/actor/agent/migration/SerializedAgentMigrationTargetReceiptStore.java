package com.commonbattle.actor.agent.migration;

import com.commonbattle.persistence.AtomicBytesStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于原子字节存储的目标迁入回执存储。
 * 线上可把 AtomicBytesStore 替换为 Redis、DB 或 WAL，使目标服重启后仍能识别同一迁移任务的重复 accept。
 */
public final class SerializedAgentMigrationTargetReceiptStore implements AgentMigrationTargetReceiptStore {
    public static final String DEFAULT_PREFIX = "agent:migration:target-receipt:";

    private final AtomicBytesStore store;
    private final AgentMigrationTargetReceiptSerializer serializer;
    private final Clock clock;
    private final String prefix;

    public SerializedAgentMigrationTargetReceiptStore(
            AtomicBytesStore store,
            AgentMigrationTargetReceiptSerializer serializer,
            Clock clock
    ) {
        this(store, serializer, clock, DEFAULT_PREFIX);
    }

    public SerializedAgentMigrationTargetReceiptStore(
            AtomicBytesStore store,
            AgentMigrationTargetReceiptSerializer serializer,
            Clock clock,
            String prefix
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
    }

    @Override
    public Optional<AgentMigrationAcceptResponse> find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return store.load(key(taskId))
                .map(serializer::decode)
                .map(AgentMigrationTargetReceipt::response);
    }

    @Override
    public void save(String taskId, AgentMigrationAcceptResponse response) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        AgentMigrationTargetReceipt receipt = new AgentMigrationTargetReceipt(
                taskId,
                Objects.requireNonNull(response, "response"),
                clock.instant()
        );
        store.put(key(taskId), serializer.encode(receipt));
    }

    public int purgeBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        int purged = 0;
        for (AtomicBytesStore.Entry entry : store.scanPrefix(prefix)) {
            AgentMigrationTargetReceipt receipt = serializer.decode(entry.bytes());
            if (receipt.updatedAt().isBefore(cutoff)
                    && store.compareAndDelete(entry.key(), entry.bytes())) {
                purged++;
            }
        }
        return purged;
    }

    public int size() {
        return store.scanPrefix(prefix).size();
    }

    private String key(String taskId) {
        return prefix + taskId;
    }
}
