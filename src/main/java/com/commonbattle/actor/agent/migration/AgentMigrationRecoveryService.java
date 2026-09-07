package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Agent 迁移恢复器。
 * 服务重启后扫描待接收任务，继续通知目标；如果目标无法接收，则尝试把目录回滚到源 owner。
 */
public final class AgentMigrationRecoveryService {
    private final AgentMigrationTaskStore taskStore;
    private final AgentDirectory directory;
    private final AgentLifecycleManager sourceLifecycles;
    private final AgentMigrationClient client;
    private final Executor executor;
    private final AgentMigrationPolicy policy;
    private final Clock clock;
    private final String recoveryOwner;
    private final Duration leaseTtl;
    private final AgentMigrationRecoveryMetrics metrics = new AgentMigrationRecoveryMetrics();

    public AgentMigrationRecoveryService(
            AgentMigrationTaskStore taskStore,
            AgentDirectory directory,
            AgentLifecycleManager sourceLifecycles,
            AgentMigrationClient client,
            Executor executor,
            AgentMigrationPolicy policy,
            Clock clock
    ) {
        this(taskStore, directory, sourceLifecycles, client, executor, policy, clock,
                "migration-recovery-" + java.util.UUID.randomUUID(), Duration.ofSeconds(30));
    }

    public AgentMigrationRecoveryService(
            AgentMigrationTaskStore taskStore,
            AgentDirectory directory,
            AgentLifecycleManager sourceLifecycles,
            AgentMigrationClient client,
            Executor executor,
            AgentMigrationPolicy policy,
            Clock clock,
            String recoveryOwner,
            Duration leaseTtl
    ) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.sourceLifecycles = Objects.requireNonNull(sourceLifecycles, "sourceLifecycles");
        this.client = Objects.requireNonNull(client, "client");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recoveryOwner = Objects.requireNonNull(recoveryOwner, "recoveryOwner");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (recoveryOwner.isBlank()) {
            throw new IllegalArgumentException("recoveryOwner must not be blank");
        }
        if (leaseTtl.isNegative() || leaseTtl.isZero()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
    }

    public int recoverPending(AgentMigrationResultCallback callback) {
        Objects.requireNonNull(callback, "callback");
        metrics.scan();
        List<AgentMigrationTask> tasks = taskStore.pendingTasks();
        int claimed = 0;
        for (AgentMigrationTask task : tasks) {
            Optional<AgentMigrationTask> claimedTask = taskStore.claim(
                    task.taskId(),
                    recoveryOwner,
                    clock.instant(),
                    leaseTtl
            );
            if (claimedTask.isEmpty()) {
                continue;
            }
            claimed++;
            try {
                AgentMigrationTask leased = claimedTask.orElseThrow();
                executor.execute(() -> recover(leased, callback));
            } catch (RejectedExecutionException e) {
                metrics.executorRejected();
                AgentMigrationTask leased = claimedTask.orElseThrow();
                boolean rolledBack = abortBeforeOrAfterMove(leased);
                callback.completed(result(
                        leased,
                        rolledBack
                                ? AgentMigrationResultStatus.COMPLETION_REJECTED_ROLLED_BACK
                                : AgentMigrationResultStatus.COMPLETION_REJECTED_ROLLBACK_FAILED,
                        e.getMessage()
                ));
            }
        }
        return claimed;
    }

    public AgentMigrationRecoveryStats stats() {
        return metrics.snapshot();
    }

    private void recover(AgentMigrationTask task, AgentMigrationResultCallback callback) {
        metrics.recoveredTask();
        AgentMigrationTask moved = ensureMoved(task);
        if (moved == null) {
            callback.completed(result(task, AgentMigrationResultStatus.SOURCE_MOVE_FAILED, "directory_move_failed"));
            return;
        }
        AgentMigrationAcceptRequest request = new AgentMigrationAcceptRequest(
                moved.identity(),
                moved.target().actorRef().id(),
                moved.snapshot().stateType(),
                moved.snapshot().stateBytes()
        );
        try {
            AgentMigrationAcceptResponse response = acceptWithRetry(moved, request);
            if (!response.accepted()) {
                metrics.targetRejected();
                boolean rolledBack = rollback(moved);
                callback.completed(result(
                        moved,
                        rolledBack
                                ? AgentMigrationResultStatus.TARGET_REJECTED_ROLLED_BACK
                                : AgentMigrationResultStatus.TARGET_REJECTED_ROLLBACK_FAILED,
                        response.reason()
                ));
                return;
            }
            metrics.targetAccepted();
            taskStore.mark(moved.taskId(), AgentMigrationTaskStatus.TARGET_ACCEPTED, "", clock.instant());
            callback.completed(result(moved, AgentMigrationResultStatus.TARGET_ACCEPTED, ""));
        } catch (RuntimeException e) {
            metrics.targetFailed();
            boolean rolledBack = rollback(moved);
            callback.completed(result(
                    moved,
                    rolledBack
                            ? AgentMigrationResultStatus.TARGET_FAILED_ROLLED_BACK
                            : AgentMigrationResultStatus.TARGET_FAILED_ROLLBACK_FAILED,
                    e.getMessage()
            ));
        }
    }

    private AgentMigrationTask ensureMoved(AgentMigrationTask task) {
        if (task.status() == AgentMigrationTaskStatus.MOVED) {
            return task;
        }
        if (directory.locate(task.identity()).filter(task.target()::equals).isPresent()) {
            taskStore.mark(task.taskId(), AgentMigrationTaskStatus.MOVED, "", clock.instant());
            return task.withStatus(AgentMigrationTaskStatus.MOVED, "", clock.instant());
        }
        if (directory.move(task.identity(), task.source(), task.target())) {
            taskStore.mark(task.taskId(), AgentMigrationTaskStatus.MOVED, "", clock.instant());
            return task.withStatus(AgentMigrationTaskStatus.MOVED, "", clock.instant());
        }
        metrics.rollbackFailed();
        taskStore.mark(task.taskId(), AgentMigrationTaskStatus.ROLLBACK_FAILED, "directory_move_failed",
                clock.instant());
        return null;
    }

    private AgentMigrationAcceptResponse acceptWithRetry(AgentMigrationTask task, AgentMigrationAcceptRequest request) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= policy.targetAcceptAttempts(); attempt++) {
            try {
                return client.accept(task.target().serviceId(), request);
            } catch (RuntimeException e) {
                last = e;
                if (attempt < policy.targetAcceptAttempts()) {
                    metrics.targetRetry();
                }
            }
        }
        throw last == null ? new IllegalStateException("Agent migration recovery target accept failed") : last;
    }

    private boolean rollback(AgentMigrationTask task) {
        if (directory.move(task.identity(), task.target(), task.source())) {
            sourceLifecycles.resumeAfterMigrationRollback(task.identity(), task.source());
            metrics.rollbackSucceeded();
            taskStore.mark(task.taskId(), AgentMigrationTaskStatus.ROLLED_BACK, "", clock.instant());
            return true;
        }
        metrics.rollbackFailed();
        taskStore.mark(task.taskId(), AgentMigrationTaskStatus.ROLLBACK_FAILED, "directory_move_failed",
                clock.instant());
        return false;
    }

    private boolean abortBeforeOrAfterMove(AgentMigrationTask task) {
        if (task.status() == AgentMigrationTaskStatus.PREPARED
                && directory.locate(task.identity()).filter(task.source()::equals).isPresent()) {
            sourceLifecycles.resumeAfterMigrationRollback(task.identity(), task.source());
            metrics.rollbackSucceeded();
            taskStore.mark(task.taskId(), AgentMigrationTaskStatus.ROLLED_BACK, "", clock.instant());
            return true;
        }
        return rollback(task);
    }

    private static AgentMigrationResult result(
            AgentMigrationTask task,
            AgentMigrationResultStatus status,
            String reason
    ) {
        return new AgentMigrationResult(task.identity(), task.source(), task.target(), status, reason);
    }
}
