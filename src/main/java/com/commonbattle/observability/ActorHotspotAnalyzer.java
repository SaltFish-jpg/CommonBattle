package com.commonbattle.observability;

import com.commonbattle.actor.ActorMailboxStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Actor 热点治理分析器。
 * 它只合并邮箱积压和最近慢任务两个信号，输出建议动作，不直接改变 Actor 调度或业务状态。
 */
public final class ActorHotspotAnalyzer {
    private ActorHotspotAnalyzer() {
    }

    public static List<ActorHotspotCandidate> analyze(
            List<ActorMailboxStats> mailboxes,
            List<ActorSlowTaskRecord> slowTasks,
            ActorHotspotPolicy policy
    ) {
        Objects.requireNonNull(mailboxes, "mailboxes");
        Objects.requireNonNull(slowTasks, "slowTasks");
        Objects.requireNonNull(policy, "policy");
        Map<String, Accumulator> actors = new HashMap<>();
        for (ActorMailboxStats mailbox : mailboxes) {
            String actorId = mailbox.actor().id();
            Accumulator accumulator = actors.computeIfAbsent(actorId, Accumulator::new);
            accumulator.queuedTasks = mailbox.queuedTasks();
            accumulator.mailboxBatchYields = mailbox.batchYields();
        }
        for (ActorSlowTaskRecord slowTask : slowTasks) {
            Accumulator accumulator = actors.computeIfAbsent(slowTask.actorId(), Accumulator::new);
            accumulator.recentSlowTasks++;
            accumulator.maxSlowTaskMillis = Math.max(accumulator.maxSlowTaskMillis, slowTask.elapsedMillis());
        }
        List<ActorHotspotCandidate> result = new ArrayList<>();
        for (Accumulator accumulator : actors.values()) {
            ActorHotspotAction action = actionOf(accumulator, policy);
            if (action == null) {
                continue;
            }
            Set<ActorHotspotReason> reasons = reasonsOf(accumulator, policy, action);
            result.add(new ActorHotspotCandidate(
                    accumulator.actorId,
                    ActorMailboxDiagnostics.groupOfActorId(accumulator.actorId),
                    accumulator.queuedTasks,
                    accumulator.recentSlowTasks,
                    accumulator.maxSlowTaskMillis,
                    accumulator.mailboxBatchYields,
                    action,
                    reasons,
                    scoreOf(accumulator, action)
            ));
        }
        return result.stream()
                .sorted(Comparator.comparingLong(ActorHotspotCandidate::score).reversed()
                        .thenComparing(ActorHotspotCandidate::queuedTasks, Comparator.reverseOrder())
                        .thenComparing(ActorHotspotCandidate::maxSlowTaskMillis, Comparator.reverseOrder())
                        .thenComparing(ActorHotspotCandidate::actorId))
                .toList();
    }

    private static ActorHotspotAction actionOf(Accumulator accumulator, ActorHotspotPolicy policy) {
        if (accumulator.queuedTasks >= policy.migrationQueuedTasks()
                || accumulator.recentSlowTasks >= policy.migrationSlowTasks()
                || accumulator.maxSlowTaskMillis >= policy.migrationSlowTaskMillis()
                || accumulator.mailboxBatchYields >= policy.migrationBatchYields()) {
            return ActorHotspotAction.MIGRATION_CANDIDATE;
        }
        if (accumulator.queuedTasks >= policy.throttleQueuedTasks()
                || accumulator.recentSlowTasks >= policy.throttleSlowTasks()
                || accumulator.maxSlowTaskMillis >= policy.throttleSlowTaskMillis()
                || accumulator.mailboxBatchYields >= policy.throttleBatchYields()) {
            return ActorHotspotAction.THROTTLE;
        }
        if (accumulator.queuedTasks >= policy.observeQueuedTasks()
                || accumulator.recentSlowTasks > 0
                || accumulator.mailboxBatchYields > 0) {
            return ActorHotspotAction.OBSERVE;
        }
        return null;
    }

    private static Set<ActorHotspotReason> reasonsOf(
            Accumulator accumulator,
            ActorHotspotPolicy policy,
            ActorHotspotAction action
    ) {
        EnumSet<ActorHotspotReason> reasons = EnumSet.noneOf(ActorHotspotReason.class);
        switch (action) {
            case MIGRATION_CANDIDATE -> {
                addIf(reasons, ActorHotspotReason.QUEUE_BACKLOG,
                        accumulator.queuedTasks >= policy.migrationQueuedTasks());
                addIf(reasons, ActorHotspotReason.SLOW_TASK_COUNT,
                        accumulator.recentSlowTasks >= policy.migrationSlowTasks());
                addIf(reasons, ActorHotspotReason.SLOW_TASK_MILLIS,
                        accumulator.maxSlowTaskMillis >= policy.migrationSlowTaskMillis());
                addIf(reasons, ActorHotspotReason.MAILBOX_BATCH_YIELD,
                        accumulator.mailboxBatchYields >= policy.migrationBatchYields());
            }
            case THROTTLE -> {
                addIf(reasons, ActorHotspotReason.QUEUE_BACKLOG,
                        accumulator.queuedTasks >= policy.throttleQueuedTasks());
                addIf(reasons, ActorHotspotReason.SLOW_TASK_COUNT,
                        accumulator.recentSlowTasks >= policy.throttleSlowTasks());
                addIf(reasons, ActorHotspotReason.SLOW_TASK_MILLIS,
                        accumulator.maxSlowTaskMillis >= policy.throttleSlowTaskMillis());
                addIf(reasons, ActorHotspotReason.MAILBOX_BATCH_YIELD,
                        accumulator.mailboxBatchYields >= policy.throttleBatchYields());
            }
            case OBSERVE -> {
                addIf(reasons, ActorHotspotReason.QUEUE_BACKLOG,
                        accumulator.queuedTasks >= policy.observeQueuedTasks());
                addIf(reasons, ActorHotspotReason.SLOW_TASK_COUNT,
                        accumulator.recentSlowTasks > 0);
                addIf(reasons, ActorHotspotReason.MAILBOX_BATCH_YIELD,
                        accumulator.mailboxBatchYields > 0);
            }
        }
        return reasons;
    }

    private static void addIf(EnumSet<ActorHotspotReason> reasons, ActorHotspotReason reason, boolean matched) {
        if (matched) {
            reasons.add(reason);
        }
    }

    private static long scoreOf(Accumulator accumulator, ActorHotspotAction action) {
        return severity(action) * 1_000_000_000L
                + accumulator.queuedTasks * 10_000L
                + accumulator.mailboxBatchYields * 5_000L
                + accumulator.recentSlowTasks * 1_000L
                + accumulator.maxSlowTaskMillis;
    }

    private static int severity(ActorHotspotAction action) {
        return switch (action) {
            case OBSERVE -> 1;
            case THROTTLE -> 2;
            case MIGRATION_CANDIDATE -> 3;
        };
    }

    private static final class Accumulator {
        private final String actorId;
        private int queuedTasks;
        private int recentSlowTasks;
        private long maxSlowTaskMillis;
        private long mailboxBatchYields;

        private Accumulator(String actorId) {
            this.actorId = actorId;
        }
    }
}
