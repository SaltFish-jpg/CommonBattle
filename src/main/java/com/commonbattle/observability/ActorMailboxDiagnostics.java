package com.commonbattle.observability;

import com.commonbattle.actor.ActorMailboxStats;
import com.commonbattle.actor.ActorTaskCategory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Actor 邮箱热点诊断快照。
 * 它把具体 Actor 邮箱快照聚合为低基数业务组，同时保留少量最热 Actor 明细给健康接口排障。
 */
public record ActorMailboxDiagnostics(
        int activeMailboxes,
        int queuedTasks,
        int largestMailboxQueuedTasks,
        String largestMailboxActorId,
        List<ActorMailboxGroupStats> groups,
        List<ActorMailboxStats> hottestMailboxes
) {
    private static final int DEFAULT_HOT_LIMIT = 10;

    public ActorMailboxDiagnostics {
        Objects.requireNonNull(largestMailboxActorId, "largestMailboxActorId");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(hottestMailboxes, "hottestMailboxes");
        if (activeMailboxes < 0 || queuedTasks < 0 || largestMailboxQueuedTasks < 0) {
            throw new IllegalArgumentException("mailbox diagnostics must not be negative");
        }
        groups = List.copyOf(groups);
        hottestMailboxes = List.copyOf(hottestMailboxes);
    }

    public static ActorMailboxDiagnostics empty() {
        return new ActorMailboxDiagnostics(0, 0, 0, "", List.of(), List.of());
    }

    public static ActorMailboxDiagnostics from(Collection<ActorMailboxStats> mailboxes) {
        return from(mailboxes, DEFAULT_HOT_LIMIT);
    }

    public static ActorMailboxDiagnostics from(Collection<ActorMailboxStats> mailboxes, int hotLimit) {
        Objects.requireNonNull(mailboxes, "mailboxes");
        if (mailboxes.isEmpty()) {
            return empty();
        }
        int activeMailboxes = 0;
        int queuedTasks = 0;
        int largestMailboxQueuedTasks = 0;
        String largestMailboxActorId = "";
        Map<String, GroupAccumulator> groups = new HashMap<>();
        List<ActorMailboxStats> active = new ArrayList<>();
        for (ActorMailboxStats mailbox : mailboxes) {
            if (mailbox.queuedTasks() <= 0) {
                continue;
            }
            activeMailboxes++;
            queuedTasks += mailbox.queuedTasks();
            active.add(mailbox);
            if (mailbox.queuedTasks() > largestMailboxQueuedTasks) {
                largestMailboxQueuedTasks = mailbox.queuedTasks();
                largestMailboxActorId = mailbox.actor().id();
            }
            groups.computeIfAbsent(groupOfActorId(mailbox.actor().id()), GroupAccumulator::new).add(mailbox);
        }
        if (activeMailboxes == 0) {
            return empty();
        }
        List<ActorMailboxGroupStats> grouped = groups.values().stream()
                .map(GroupAccumulator::snapshot)
                .sorted(Comparator.comparingInt(ActorMailboxGroupStats::queuedTasks)
                        .reversed()
                        .thenComparing(ActorMailboxGroupStats::group))
                .toList();
        List<ActorMailboxStats> hottest = active.stream()
                .sorted(Comparator.comparingInt(ActorMailboxStats::queuedTasks)
                        .reversed()
                        .thenComparing(stats -> stats.actor().id()))
                .limit(Math.max(0, hotLimit))
                .toList();
        return new ActorMailboxDiagnostics(activeMailboxes, queuedTasks, largestMailboxQueuedTasks,
                largestMailboxActorId, grouped, hottest);
    }

    public static String groupOfActorId(String actorId) {
        Objects.requireNonNull(actorId, "actorId");
        if (actorId.startsWith("player-autosave:")) {
            return "player-autosave";
        }
        if (actorId.startsWith("scene-profile:")) {
            return "scene-profile";
        }
        if (actorId.startsWith("scene-domain-events:")
                || actorId.startsWith("scene-alliance-events:")
                || actorId.startsWith("scene-friend-events:")) {
            return "scene-events";
        }
        if (actorId.startsWith("scene-shard:")) {
            return "scene-shard";
        }
        if (actorId.startsWith("player-agent-")) {
            return "player-agent";
        }
        if (actorId.startsWith("player-")) {
            return "player";
        }
        if (actorId.startsWith("scene-")) {
            return "scene";
        }
        if (actorId.startsWith("chat-")) {
            return "chat";
        }
        if (actorId.startsWith("shop-")) {
            return "shop";
        }
        int separator = actorId.indexOf(':');
        if (separator > 0) {
            return actorId.substring(0, separator);
        }
        separator = actorId.indexOf('-');
        if (separator > 0) {
            return actorId.substring(0, separator);
        }
        return "other";
    }

    private static final class GroupAccumulator {
        private final String group;
        private final EnumMap<ActorTaskCategory, Integer> queuedByCategory = new EnumMap<>(ActorTaskCategory.class);
        private int activeMailboxes;
        private int queuedTasks;
        private int largestMailboxQueuedTasks;
        private String largestMailboxActorId = "";

        private GroupAccumulator(String group) {
            this.group = group;
            for (ActorTaskCategory category : ActorTaskCategory.values()) {
                queuedByCategory.put(category, 0);
            }
        }

        private void add(ActorMailboxStats mailbox) {
            activeMailboxes++;
            queuedTasks += mailbox.queuedTasks();
            if (mailbox.queuedTasks() > largestMailboxQueuedTasks) {
                largestMailboxQueuedTasks = mailbox.queuedTasks();
                largestMailboxActorId = mailbox.actor().id();
            }
            for (Map.Entry<ActorTaskCategory, Integer> entry : mailbox.queuedTasksByCategory().entrySet()) {
                queuedByCategory.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }

        private ActorMailboxGroupStats snapshot() {
            return new ActorMailboxGroupStats(group, activeMailboxes, queuedTasks,
                    largestMailboxQueuedTasks, largestMailboxActorId, queuedByCategory);
        }
    }
}
