package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentMigrationCompletion;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 源服务 Agent 迁移协调器。
 * 它把“源邮箱打包、目录 CAS move、定点通知目标接收、失败回滚”串成一个可复用流程。
 */
public final class AgentMigrationCoordinator {
    private final AgentLifecycleManager sourceLifecycles;
    private final AgentDirectory directory;
    private final AgentMigrationClient client;
    private final Executor completionExecutor;
    private final AgentMigrationPolicy policy;
    private final AgentMigrationTaskStore taskStore;
    private final AgentMigrationTaskIdGenerator taskIds;
    private final Clock clock;
    private final AgentMigrationSourceHook sourceHook;
    private final AgentMigrationCoordinatorMetrics metrics = new AgentMigrationCoordinatorMetrics();

    public AgentMigrationCoordinator(
            AgentLifecycleManager sourceLifecycles,
            AgentDirectory directory,
            AgentMigrationClient client,
            Executor completionExecutor
    ) {
        this(sourceLifecycles, directory, client, completionExecutor, AgentMigrationPolicy.defaults());
    }

    public AgentMigrationCoordinator(
            AgentLifecycleManager sourceLifecycles,
            AgentDirectory directory,
            AgentMigrationClient client,
            Executor completionExecutor,
            AgentMigrationPolicy policy
    ) {
        this(sourceLifecycles, directory, client, completionExecutor, policy,
                AgentMigrationTaskStore.none(), AgentMigrationTaskIdGenerator.defaultGenerator(), Clock.systemUTC());
    }

    public AgentMigrationCoordinator(
            AgentLifecycleManager sourceLifecycles,
            AgentDirectory directory,
            AgentMigrationClient client,
            Executor completionExecutor,
            AgentMigrationPolicy policy,
            AgentMigrationTaskStore taskStore,
            AgentMigrationTaskIdGenerator taskIds,
            Clock clock
    ) {
        this(sourceLifecycles, directory, client, completionExecutor, policy, taskStore, taskIds, clock,
                AgentMigrationSourceHook.noop());
    }

    public AgentMigrationCoordinator(
            AgentLifecycleManager sourceLifecycles,
            AgentDirectory directory,
            AgentMigrationClient client,
            Executor completionExecutor,
            AgentMigrationPolicy policy,
            AgentMigrationTaskStore taskStore,
            AgentMigrationTaskIdGenerator taskIds,
            Clock clock,
            AgentMigrationSourceHook sourceHook
    ) {
        this.sourceLifecycles = Objects.requireNonNull(sourceLifecycles, "sourceLifecycles");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.client = Objects.requireNonNull(client, "client");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.taskIds = Objects.requireNonNull(taskIds, "taskIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sourceHook = Objects.requireNonNull(sourceHook, "sourceHook");
    }

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
        AgentLocation source = sourceLifecycles.record(identity).orElseThrow().location();
        AtomicReference<AgentMigrationTask> task = new AtomicReference<>();
        metrics.initiated();
        return sourceLifecycles.migrate(
                identity,
                target,
                context -> {
                    AgentMigrationSnapshot snapshot = Objects.requireNonNull(
                            packer.pack(identity, target, context),
                            "snapshot"
                    );
                    AgentMigrationTask prepared = preparedTask(identity, source, target, snapshot);
                    task.set(prepared);
                    taskStore.save(prepared);
                },
                completion -> onSourceMoved(completion, task.get(), callback)
        );
    }

    public AgentMigrationCoordinatorStats stats() {
        return metrics.snapshot();
    }

    private void onSourceMoved(
            AgentMigrationCompletion completion,
            AgentMigrationTask task,
            AgentMigrationResultCallback callback
    ) {
        if (!completion.moved()) {
            metrics.sourceMoveFailed();
            submitResult(callback, result(
                    completion,
                    AgentMigrationResultStatus.SOURCE_MOVE_FAILED,
                    completion.failedCause().map(Throwable::getMessage).orElse("")
            ));
            return;
        }
        metrics.sourceMoved();
        AgentMigrationTask moved = task == null ? movedTask(completion) : task.withStatus(
                AgentMigrationTaskStatus.MOVED,
                "",
                clock.instant()
        );
        taskStore.mark(moved.taskId(), AgentMigrationTaskStatus.MOVED, "", moved.updatedAt());
        try {
            sourceHook.sourceMoved(moved);
        } catch (RuntimeException e) {
            boolean rolledBack = rollback(moved);
            callback.completed(result(
                    moved,
                    rolledBack
                            ? AgentMigrationResultStatus.TARGET_FAILED_ROLLED_BACK
                            : AgentMigrationResultStatus.TARGET_FAILED_ROLLBACK_FAILED,
                    "source_cleanup_failed:" + messageOf(e)
            ));
            return;
        }
        try {
            completionExecutor.execute(() -> acceptTargetOrRollback(moved, callback));
        } catch (RejectedExecutionException e) {
            metrics.completionRejected();
            boolean rolledBack = rollback(moved);
            callback.completed(result(
                    moved,
                    rolledBack
                            ? AgentMigrationResultStatus.COMPLETION_REJECTED_ROLLED_BACK
                            : AgentMigrationResultStatus.COMPLETION_REJECTED_ROLLBACK_FAILED,
                    e.getMessage()
            ));
        }
    }

    private void acceptTargetOrRollback(
            AgentMigrationTask task,
            AgentMigrationResultCallback callback
    ) {
        AgentMigrationAcceptRequest request = new AgentMigrationAcceptRequest(
                task.taskId(),
                task.identity(),
                task.target().actorRef().id(),
                task.snapshot().stateType(),
                task.snapshot().stateBytes()
        );
        try {
            AgentMigrationAcceptResponse response = acceptWithRetry(task, request);
            if (!response.accepted()) {
                metrics.targetRejected();
                boolean rolledBack = rollback(task);
                callback.completed(result(
                        task,
                        rolledBack
                                ? AgentMigrationResultStatus.TARGET_REJECTED_ROLLED_BACK
                                : AgentMigrationResultStatus.TARGET_REJECTED_ROLLBACK_FAILED,
                        response.reason()
                ));
                return;
            }
            metrics.targetAccepted();
            taskStore.mark(task.taskId(), AgentMigrationTaskStatus.TARGET_ACCEPTED, "", clock.instant());
            callback.completed(result(task, AgentMigrationResultStatus.TARGET_ACCEPTED, ""));
        } catch (RuntimeException e) {
            metrics.targetFailed();
            boolean rolledBack = rollback(task);
            callback.completed(result(
                    task,
                    rolledBack
                            ? AgentMigrationResultStatus.TARGET_FAILED_ROLLED_BACK
                            : AgentMigrationResultStatus.TARGET_FAILED_ROLLBACK_FAILED,
                    e.getMessage()
            ));
        }
    }

    private AgentMigrationAcceptResponse acceptWithRetry(
            AgentMigrationTask task,
            AgentMigrationAcceptRequest request
    ) {
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
        throw last == null ? new IllegalStateException("Agent migration target accept failed") : last;
    }

    private boolean rollback(AgentMigrationTask task) {
        if (directory.move(task.identity(), task.target(), task.source())) {
            sourceLifecycles.resumeAfterMigrationRollback(task.identity(), task.source());
            try {
                sourceHook.rollbackRestored(task);
                metrics.rollbackSucceeded();
                taskStore.mark(task.taskId(), AgentMigrationTaskStatus.ROLLED_BACK, "", clock.instant());
                return true;
            } catch (RuntimeException e) {
                metrics.rollbackFailed();
                taskStore.mark(task.taskId(), AgentMigrationTaskStatus.ROLLBACK_FAILED,
                        "source_restore_failed:" + messageOf(e), clock.instant());
                return false;
            }
        } else {
            metrics.rollbackFailed();
            taskStore.mark(task.taskId(), AgentMigrationTaskStatus.ROLLBACK_FAILED, "directory_move_failed",
                    clock.instant());
            return false;
        }
    }

    private AgentMigrationTask preparedTask(
            AgentIdentity identity,
            AgentLocation source,
            AgentLocation target,
            AgentMigrationSnapshot snapshot
    ) {
        java.time.Instant now = clock.instant();
        return new AgentMigrationTask(
                taskIds.nextId(identity, source, target, now),
                identity,
                source,
                target,
                snapshot,
                AgentMigrationTaskStatus.PREPARED,
                "",
                now
        );
    }

    private AgentMigrationTask movedTask(AgentMigrationCompletion completion) {
        java.time.Instant now = clock.instant();
        return new AgentMigrationTask(
                taskIds.nextId(completion.identity(), completion.source(), completion.target(), now),
                completion.identity(),
                completion.source(),
                completion.target(),
                new AgentMigrationSnapshot("unknown", new byte[0]),
                AgentMigrationTaskStatus.MOVED,
                "missing_prepared_task",
                now
        );
    }

    private void submitResult(AgentMigrationResultCallback callback, AgentMigrationResult result) {
        try {
            completionExecutor.execute(() -> callback.completed(result));
        } catch (RejectedExecutionException ignored) {
            callback.completed(result);
        }
    }

    private static AgentMigrationResult result(
            AgentMigrationCompletion completion,
            AgentMigrationResultStatus status,
            String reason
    ) {
        return new AgentMigrationResult(
                completion.identity(),
                completion.source(),
                completion.target(),
                status,
                reason
        );
    }

    private static AgentMigrationResult result(
            AgentMigrationTask task,
            AgentMigrationResultStatus status,
            String reason
    ) {
        return new AgentMigrationResult(
                task.identity(),
                task.source(),
                task.target(),
                status,
                reason
        );
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getName() : e.getMessage();
    }
}
