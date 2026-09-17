package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.ActorMailboxStats;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.observability.ActorMailboxDiagnostics;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * 基于 Actor 邮箱积压的准入控制器。
 * 它包装已有准入控制，只有在基础准入通过后才读取邮箱快照，避免热点玩家继续向已积压的 Actor 投递命令。
 */
public final class ActorMailboxPressureAdmissionController implements InboundAdmissionController, ActorMailboxPressureView {
    public static final String TARGET_PRESSURE_REASON = "mailbox_pressure:target";
    public static final String GROUP_PRESSURE_REASON = "mailbox_pressure:group";

    private final InboundAdmissionController delegate;
    private final ActorSystem actors;
    private final ActorMailboxPressurePolicy policy;
    private final Function<AgentIdentity, String> actorIdResolver;
    private final LongAdder admissions = new LongAdder();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder delegateRejected = new LongAdder();
    private final LongAdder pressureRejected = new LongAdder();
    private final LongAdder targetPressureRejected = new LongAdder();
    private final LongAdder groupPressureRejected = new LongAdder();
    private final Map<String, LongAdder> rejectedByTargetType = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> rejectedByActorGroup = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> rejectedByReason = new ConcurrentHashMap<>();

    public ActorMailboxPressureAdmissionController(
            InboundAdmissionController delegate,
            ActorSystem actors,
            ActorMailboxPressurePolicy policy
    ) {
        this(delegate, actors, policy, ActorMailboxPressureAdmissionController::defaultActorId);
    }

    public ActorMailboxPressureAdmissionController(
            InboundAdmissionController delegate,
            ActorSystem actors,
            ActorMailboxPressurePolicy policy,
            Function<AgentIdentity, String> actorIdResolver
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.actorIdResolver = Objects.requireNonNull(actorIdResolver, "actorIdResolver");
    }

    @Override
    public AdmissionDecision admit(AgentIdentity target, String operation) {
        admissions.increment();
        AdmissionDecision decision = delegate.admit(target, operation);
        if (!decision.accepted()) {
            delegateRejected.increment();
            return decision;
        }
        if (!policy.active()) {
            accepted.increment();
            return decision;
        }
        String actorId = actorIdResolver.apply(target);
        String group = ActorMailboxDiagnostics.groupOfActorId(actorId);
        List<ActorMailboxStats> mailboxes = actors.queuedMailboxStats();
        int targetQueuedTasks = 0;
        int groupQueuedTasks = 0;
        for (ActorMailboxStats mailbox : mailboxes) {
            if (mailbox.actor().id().equals(actorId)) {
                targetQueuedTasks = mailbox.queuedTasks();
            }
            if (ActorMailboxDiagnostics.groupOfActorId(mailbox.actor().id()).equals(group)) {
                groupQueuedTasks += mailbox.queuedTasks();
            }
        }
        if (policy.hasTargetLimit() && targetQueuedTasks >= policy.maxTargetQueuedTasks()) {
            return reject(target, group, TARGET_PRESSURE_REASON, true);
        }
        if (policy.hasGroupLimit() && groupQueuedTasks >= policy.maxGroupQueuedTasks()) {
            return reject(target, group, GROUP_PRESSURE_REASON, false);
        }
        accepted.increment();
        return decision;
    }

    @Override
    public ActorMailboxPressureStats mailboxPressureStats() {
        return new ActorMailboxPressureStats(
                admissions.sum(),
                accepted.sum(),
                delegateRejected.sum(),
                pressureRejected.sum(),
                targetPressureRejected.sum(),
                groupPressureRejected.sum(),
                snapshot(rejectedByTargetType),
                snapshot(rejectedByActorGroup),
                snapshot(rejectedByReason)
        );
    }

    private AdmissionDecision reject(AgentIdentity target, String group, String reason, boolean targetLimit) {
        pressureRejected.increment();
        if (targetLimit) {
            targetPressureRejected.increment();
        } else {
            groupPressureRejected.increment();
        }
        increment(rejectedByTargetType, target.type());
        increment(rejectedByActorGroup, group);
        increment(rejectedByReason, reason);
        Duration retryAfter = policy.retryAfter();
        return AdmissionDecision.reject(reason, retryAfter);
    }

    private static void increment(Map<String, LongAdder> counters, String key) {
        counters.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    private static Map<String, Long> snapshot(Map<String, LongAdder> counters) {
        Map<String, Long> result = new ConcurrentHashMap<>();
        counters.forEach((key, value) -> result.put(key, value.sum()));
        return Map.copyOf(result);
    }

    private static String defaultActorId(AgentIdentity identity) {
        return switch (identity.type()) {
            case AgentIdentity.PLAYER -> "player-" + identity.key();
            case AgentIdentity.FRIEND -> "friend-" + identity.key();
            case AgentIdentity.ALLIANCE -> "alliance-" + identity.key();
            case AgentIdentity.SCENE -> "scene:" + identity.key();
            default -> identity.type() + ":" + identity.key();
        };
    }
}
