package com.commonbattle.observability;

import com.commonbattle.actor.ActorMailboxStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
            actors.computeIfAbsent(actorId, Accumulator::new).queuedTasks = mailbox.queuedTasks();
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
            result.add(new ActorHotspotCandidate(
                    accumulator.actorId,
                    ActorMailboxDiagnostics.groupOfActorId(accumulator.actorId),
                    accumulator.queuedTasks,
                    accumulator.recentSlowTasks,
                    accumulator.maxSlowTaskMillis,
                    action
            ));
        }
        return result.stream()
                .sorted(Comparator.comparingInt((ActorHotspotCandidate candidate) -> severity(candidate.action()))
                        .reversed()
                        .thenComparing(ActorHotspotCandidate::queuedTasks, Comparator.reverseOrder())
                        .thenComparing(ActorHotspotCandidate::maxSlowTaskMillis, Comparator.reverseOrder())
                        .thenComparing(ActorHotspotCandidate::actorId))
                .toList();
    }

    private static ActorHotspotAction actionOf(Accumulator accumulator, ActorHotspotPolicy policy) {
        if (accumulator.queuedTasks >= policy.migrationQueuedTasks()
                || accumulator.recentSlowTasks >= policy.migrationSlowTasks()
                || accumulator.maxSlowTaskMillis >= policy.migrationSlowTaskMillis()) {
            return ActorHotspotAction.MIGRATION_CANDIDATE;
        }
        if (accumulator.queuedTasks >= policy.throttleQueuedTasks()
                || accumulator.recentSlowTasks >= policy.throttleSlowTasks()
                || accumulator.maxSlowTaskMillis >= policy.throttleSlowTaskMillis()) {
            return ActorHotspotAction.THROTTLE;
        }
        if (accumulator.queuedTasks >= policy.observeQueuedTasks() || accumulator.recentSlowTasks > 0) {
            return ActorHotspotAction.OBSERVE;
        }
        return null;
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

        private Accumulator(String actorId) {
            this.actorId = actorId;
        }
    }
}
