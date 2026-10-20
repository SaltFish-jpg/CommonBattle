package com.commonbattle.game.player;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.migration.ActorHotspotMigrationSubmitter;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinator;
import com.commonbattle.actor.agent.migration.AgentMigrationResult;
import com.commonbattle.actor.agent.migration.AgentMigrationResultCallback;
import com.commonbattle.actor.agent.migration.AgentMigrationResultStatus;
import com.commonbattle.actor.agent.migration.AgentMigrationStatePacker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家 Agent 源侧迁移重试器。
 * pending async 命令或未完成业务响应会阻止源邮箱打包快照，这类失败不应进入目标恢复/回滚链路，
 * 而是在源 owner 保持 ACTIVE 后稍后重试。
 */
public final class PlayerAgentMigrationRetryService implements ActorHotspotMigrationSubmitter {
    private final AgentMigrationCoordinator coordinator;
    private final Map<AgentIdentity, PendingMigration> pending = new LinkedHashMap<>();
    private final AtomicLong retryableSourceFailures = new AtomicLong();
    private final AtomicLong retryAttempts = new AtomicLong();
    private final AtomicLong retrySubmitted = new AtomicLong();
    private final AtomicLong retryCompleted = new AtomicLong();

    public PlayerAgentMigrationRetryService(AgentMigrationCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public boolean migrate(AgentIdentity identity, AgentLocation target, AgentMigrationStatePacker packer) {
        return migrate(identity, target, packer, AgentMigrationResultCallback.ignore());
    }

    public boolean migrate(
            AgentIdentity identity,
            AgentLocation target,
            AgentMigrationStatePacker packer,
            AgentMigrationResultCallback callback
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(packer, "packer");
        Objects.requireNonNull(callback, "callback");
        return coordinator.migrate(identity, target, packer,
                result -> handleResult(new PendingMigration(identity, target, packer, callback), result));
    }

    public int retryPending() {
        List<PendingMigration> retries;
        synchronized (pending) {
            retries = List.copyOf(pending.values());
        }
        int submitted = 0;
        for (PendingMigration retry : retries) {
            retryAttempts.incrementAndGet();
            boolean accepted = coordinator.migrate(retry.identity(), retry.target(), retry.packer(),
                    result -> handleResult(retry, result));
            if (accepted) {
                submitted++;
                retrySubmitted.incrementAndGet();
            }
        }
        return submitted;
    }

    public PlayerAgentMigrationRetryStats stats() {
        synchronized (pending) {
            return new PlayerAgentMigrationRetryStats(
                    pending.size(),
                    retryableSourceFailures.get(),
                    retryAttempts.get(),
                    retrySubmitted.get(),
                    retryCompleted.get()
            );
        }
    }

    private void handleResult(PendingMigration migration, AgentMigrationResult result) {
        if (retryableSourceFailure(result)) {
            synchronized (pending) {
                if (!pending.containsKey(migration.identity())) {
                    retryableSourceFailures.incrementAndGet();
                }
                pending.put(migration.identity(), migration);
            }
            return;
        }
        synchronized (pending) {
            if (pending.remove(migration.identity()) != null) {
                retryCompleted.incrementAndGet();
            }
        }
        migration.callback().completed(result);
    }

    private static boolean retryableSourceFailure(AgentMigrationResult result) {
        if (result.status() != AgentMigrationResultStatus.SOURCE_MOVE_FAILED
                || !result.reason().startsWith("player ")) {
            return false;
        }
        return result.reason().contains(" has pending async commands: ")
                || result.reason().contains(" has pending business responses: ");
    }

    private record PendingMigration(
            AgentIdentity identity,
            AgentLocation target,
            AgentMigrationStatePacker packer,
            AgentMigrationResultCallback callback
    ) {
        private PendingMigration {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(packer, "packer");
            Objects.requireNonNull(callback, "callback");
        }
    }
}
