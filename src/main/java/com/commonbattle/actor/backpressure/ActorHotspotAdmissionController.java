package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.ActorMailboxStats;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.observability.ActorHotspotAction;
import com.commonbattle.observability.ActorHotspotAnalyzer;
import com.commonbattle.observability.ActorHotspotCandidate;
import com.commonbattle.observability.ActorHotspotPolicy;
import com.commonbattle.observability.ActorMailboxDiagnostics;
import com.commonbattle.observability.ActorSlowTaskRecord;
import com.commonbattle.observability.ActorSlowTaskView;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * 基于 Actor 热点候选的动态准入控制器。
 * 它只在已有准入通过后读取热点分析结果，对建议限流或迁移候选的目标提前拒绝入站请求。
 */
public final class ActorHotspotAdmissionController implements InboundAdmissionController, ActorHotspotAdmissionView,
        ActorHotspotOverrideAdmin {
    public static final String THROTTLE_REASON = "actor_hotspot:throttle";
    public static final String MIGRATION_CANDIDATE_REASON = "actor_hotspot:migration_candidate";
    public static final String MANUAL_THROTTLE_REASON = "actor_hotspot_override:throttle";
    public static final String MANUAL_MIGRATION_CANDIDATE_REASON = "actor_hotspot_override:migration_candidate";

    private final InboundAdmissionController delegate;
    private final ActorSystem actors;
    private final Collection<ActorSlowTaskView> slowTaskViews;
    private final ActorHotspotPolicy policy;
    private final Duration retryAfter;
    private final Function<AgentIdentity, String> actorIdResolver;
    private final Clock clock;
    private final LongAdder admissions = new LongAdder();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder delegateRejected = new LongAdder();
    private final LongAdder hotspotRejected = new LongAdder();
    private final LongAdder throttleRejected = new LongAdder();
    private final LongAdder migrationCandidateRejected = new LongAdder();
    private final Map<String, LongAdder> rejectedByTargetType = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> rejectedByActorGroup = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> rejectedByReason = new ConcurrentHashMap<>();
    private final Map<String, ActorHotspotOverride> overrides = new ConcurrentHashMap<>();

    public ActorHotspotAdmissionController(
            InboundAdmissionController delegate,
            ActorSystem actors,
            Collection<ActorSlowTaskView> slowTaskViews,
            ActorHotspotPolicy policy,
            Duration retryAfter
    ) {
        this(delegate, actors, slowTaskViews, policy, retryAfter, ActorHotspotAdmissionController::defaultActorId);
    }

    public ActorHotspotAdmissionController(
            InboundAdmissionController delegate,
            ActorSystem actors,
            Collection<ActorSlowTaskView> slowTaskViews,
            ActorHotspotPolicy policy,
            Duration retryAfter,
            Function<AgentIdentity, String> actorIdResolver
    ) {
        this(delegate, actors, slowTaskViews, policy, retryAfter, actorIdResolver, Clock.systemUTC());
    }

    public ActorHotspotAdmissionController(
            InboundAdmissionController delegate,
            ActorSystem actors,
            Collection<ActorSlowTaskView> slowTaskViews,
            ActorHotspotPolicy policy,
            Duration retryAfter,
            Function<AgentIdentity, String> actorIdResolver,
            Clock clock
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.slowTaskViews = List.copyOf(Objects.requireNonNull(slowTaskViews, "slowTaskViews"));
        this.policy = Objects.requireNonNull(policy, "policy");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        this.actorIdResolver = Objects.requireNonNull(actorIdResolver, "actorIdResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AdmissionDecision admit(AgentIdentity target, String operation) {
        admissions.increment();
        AdmissionDecision decision = delegate.admit(target, operation);
        if (!decision.accepted()) {
            delegateRejected.increment();
            return decision;
        }
        String actorId = actorIdResolver.apply(target);
        ActorHotspotOverride override = override(actorId);
        if (override != null) {
            if (override.mode() == ActorHotspotOverrideMode.EXEMPT) {
                accepted.increment();
                return decision;
            }
            return reject(target, override);
        }
        ActorHotspotCandidate candidate = candidate(actorId);
        if (candidate == null || candidate.action() == ActorHotspotAction.OBSERVE) {
            accepted.increment();
            return decision;
        }
        return reject(target, candidate);
    }

    @Override
    public ActorHotspotAdmissionStats hotspotAdmissionStats() {
        return new ActorHotspotAdmissionStats(
                admissions.sum(),
                accepted.sum(),
                delegateRejected.sum(),
                hotspotRejected.sum(),
                throttleRejected.sum(),
                migrationCandidateRejected.sum(),
                snapshot(rejectedByTargetType),
                snapshot(rejectedByActorGroup),
                snapshot(rejectedByReason)
        );
    }

    @Override
    public List<ActorHotspotOverride> hotspotOverrides() {
        cleanupExpiredOverrides();
        return overrides.values().stream()
                .sorted(Comparator.comparing(ActorHotspotOverride::actorId))
                .toList();
    }

    @Override
    public ActorHotspotOverride setHotspotOverride(
            String actorId,
            ActorHotspotOverrideMode mode,
            Duration ttl,
            String reason
    ) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        ActorHotspotOverride override = new ActorHotspotOverride(
                actorId,
                mode,
                Instant.now(clock).plus(ttl),
                reason
        );
        overrides.put(actorId, override);
        return override;
    }

    @Override
    public int clearHotspotOverride(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        return overrides.remove(actorId) == null ? 0 : 1;
    }

    private ActorHotspotCandidate candidate(String actorId) {
        List<ActorMailboxStats> mailboxes = actors.queuedMailboxStats();
        List<ActorSlowTaskRecord> slowTasks = slowTaskViews.stream()
                .flatMap(view -> view.recentActorSlowTasks().stream())
                .toList();
        return ActorHotspotAnalyzer.analyze(mailboxes, slowTasks, policy).stream()
                .filter(entry -> entry.actorId().equals(actorId))
                .findFirst()
                .orElse(null);
    }

    private ActorHotspotOverride override(String actorId) {
        ActorHotspotOverride override = overrides.get(actorId);
        if (override == null) {
            return null;
        }
        if (override.expiredAt(Instant.now(clock))) {
            overrides.remove(actorId, override);
            return null;
        }
        return override;
    }

    private void cleanupExpiredOverrides() {
        Instant now = Instant.now(clock);
        overrides.entrySet().removeIf(entry -> entry.getValue().expiredAt(now));
    }

    private AdmissionDecision reject(AgentIdentity target, ActorHotspotCandidate candidate) {
        hotspotRejected.increment();
        String reason;
        if (candidate.action() == ActorHotspotAction.MIGRATION_CANDIDATE) {
            migrationCandidateRejected.increment();
            reason = MIGRATION_CANDIDATE_REASON;
        } else {
            throttleRejected.increment();
            reason = THROTTLE_REASON;
        }
        increment(rejectedByTargetType, target.type());
        increment(rejectedByActorGroup, candidate.group());
        increment(rejectedByReason, reason);
        return AdmissionDecision.reject(reason, retryAfter);
    }

    private AdmissionDecision reject(AgentIdentity target, ActorHotspotOverride override) {
        hotspotRejected.increment();
        String reason;
        if (override.mode() == ActorHotspotOverrideMode.MIGRATION_CANDIDATE) {
            migrationCandidateRejected.increment();
            reason = MANUAL_MIGRATION_CANDIDATE_REASON;
        } else {
            throttleRejected.increment();
            reason = MANUAL_THROTTLE_REASON;
        }
        increment(rejectedByTargetType, target.type());
        increment(rejectedByActorGroup, ActorMailboxDiagnostics.groupOfActorId(override.actorId()));
        increment(rejectedByReason, reason);
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
