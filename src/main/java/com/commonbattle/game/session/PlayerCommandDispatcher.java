package com.commonbattle.game.session;

import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionRouteResult;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.runtime.DrainableComponent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongUnaryOperator;

/**
 * 玩家命令入口分发器。
 * 它负责 session 校验、序号保护、限流和生命周期路由，业务处理器只在玩家邮箱中运行。
 */
public final class PlayerCommandDispatcher implements DrainableComponent {
    private final PlayerSessionRegistry sessions;
    private final PlayerCommandSequencer sequencer;
    private final AdmissionControlledAgentRouter router;
    private final Map<String, PlayerCommandHandler> handlers = new ConcurrentHashMap<>();
    private final PlayerCommandMetrics metrics = new PlayerCommandMetrics();
    private final PlayerCommandAuditSink auditSink;
    private final LongUnaryOperator configVersionResolver;
    private final Clock clock;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public PlayerCommandDispatcher(
            PlayerSessionRegistry sessions,
            PlayerCommandSequencer sequencer,
            AdmissionControlledAgentRouter router
    ) {
        this(sessions, sequencer, router, PlayerCommandAuditSink.NOOP, ignored -> 0, Clock.systemUTC());
    }

    public PlayerCommandDispatcher(
            PlayerSessionRegistry sessions,
            PlayerCommandSequencer sequencer,
            AdmissionControlledAgentRouter router,
            PlayerCommandAuditSink auditSink,
            LongUnaryOperator configVersionResolver,
            Clock clock
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sequencer = Objects.requireNonNull(sequencer, "sequencer");
        this.router = Objects.requireNonNull(router, "router");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.configVersionResolver = Objects.requireNonNull(configVersionResolver, "configVersionResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void handle(String operation, PlayerCommandHandler handler) {
        handlers.put(Objects.requireNonNull(operation, "operation"), Objects.requireNonNull(handler, "handler"));
    }

    public PlayerCommandStats stats() {
        return metrics.snapshot(isAccepting());
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    @Override
    public void beginDrain() {
        accepting.set(false);
    }

    @Override
    public void resumeAccepting() {
        accepting.set(true);
    }

    @Override
    public boolean isDraining() {
        return !isAccepting();
    }

    public PlayerCommandResult dispatch(PlayerCommand command) {
        Objects.requireNonNull(command, "command");
        if (!isAccepting()) {
            return result(command, PlayerCommandResult.reject(PlayerCommandStatus.DRAINING, "server_draining"),
                    PlayerCommandAuditOutcome.REJECTED, 0, Duration.ZERO, "server_draining");
        }
        PlayerCommandHandler handler = handlers.get(command.operation());
        if (handler == null) {
            return result(command, PlayerCommandResult.reject(PlayerCommandStatus.UNKNOWN_OPERATION, "unknown_operation"),
                    PlayerCommandAuditOutcome.REJECTED, 0, Duration.ZERO, "unknown_operation");
        }
        if (!sessions.isCurrent(command.playerId(), command.sessionId(), command.sessionEpoch())) {
            return result(command, PlayerCommandResult.reject(PlayerCommandStatus.STALE_SESSION, "stale_session"),
                    PlayerCommandAuditOutcome.REJECTED, 0, Duration.ZERO, "stale_session");
        }
        AdmissionRouteResult routed = router.route(AgentIdentity.player(command.playerId()), command.operation());
        if (!routed.admission().accepted()) {
            PlayerCommandResult rejected = PlayerCommandResult.reject(
                    rejectedStatus(routed.admission().reason()),
                    routed.admission().reason(),
                    routed.admission().retryAfter()
            );
            return result(command, rejected, PlayerCommandAuditOutcome.REJECTED, 0, Duration.ZERO,
                    routed.admission().reason());
        }
        PlayerCommandStatus sequenceStatus = sequencer.inspect(command);
        if (sequenceStatus == PlayerCommandStatus.DUPLICATE) {
            PlayerCommandStatus originalStatus = sequencer.committedStatus(command)
                    .orElse(PlayerCommandStatus.ACCEPTED);
            String reason = "duplicate:" + originalStatus.name();
            return result(command, PlayerCommandResult.duplicateOf(originalStatus), PlayerCommandAuditOutcome.REJECTED,
                    0, Duration.ZERO, reason);
        }
        if (sequenceStatus == PlayerCommandStatus.GAP) {
            return result(command, PlayerCommandResult.reject(PlayerCommandStatus.GAP, "command_gap"),
                    PlayerCommandAuditOutcome.REJECTED, 0, Duration.ZERO, "command_gap");
        }
        if (routed.route().type() == AgentRouteType.REMOTE) {
            return result(command, PlayerCommandResult.routedRemote(routed.route()),
                    PlayerCommandAuditOutcome.ROUTED_REMOTE, 0, Duration.ZERO, "");
        }
        AgentDeliveryResult delivery = router.deliverLocal(routed, ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND, context -> {
            long configVersion = configVersionResolver.applyAsLong(command.playerId());
            Instant startedAt = clock.instant();
            try {
                handler.handle(context, command);
                audit(command, PlayerCommandStatus.ACCEPTED, PlayerCommandAuditOutcome.EXECUTED,
                        configVersion, elapsed(startedAt), "");
            } catch (RuntimeException | Error e) {
                audit(command, PlayerCommandStatus.ACCEPTED, PlayerCommandAuditOutcome.FAILED,
                        configVersion, elapsed(startedAt), e.getMessage());
                throw e;
            }
        }));
        if (!delivery.accepted()) {
            PlayerCommandStatus status = delivery.status() == AgentDeliveryStatus.MAILBOX_FULL
                    ? PlayerCommandStatus.MAILBOX_FULL
                    : PlayerCommandStatus.AGENT_MISSING;
            return result(command, PlayerCommandResult.reject(status, delivery.reason(), delivery.retryAfter()),
                    PlayerCommandAuditOutcome.REJECTED, 0, Duration.ZERO, delivery.reason());
        }
        sequencer.commit(command, PlayerCommandStatus.ACCEPTED);
        return result(PlayerCommandResult.accepted());
    }

    private PlayerCommandResult result(
            PlayerCommand command,
            PlayerCommandResult result,
            PlayerCommandAuditOutcome outcome,
            long configVersion,
            Duration elapsed,
            String reason
    ) {
        audit(command, result.status(), outcome, configVersion, elapsed, reason);
        return result(result);
    }

    private PlayerCommandResult result(PlayerCommandResult result) {
        metrics.record(result.status());
        return result;
    }

    private PlayerCommandStatus rejectedStatus(String reason) {
        if ("rate_limited".equals(reason)) {
            return PlayerCommandStatus.RATE_LIMITED;
        }
        if (reason != null && reason.startsWith("mailbox_pressure")) {
            return PlayerCommandStatus.BACKPRESSURED;
        }
        if ("agent_migrating".equals(reason)) {
            return PlayerCommandStatus.AGENT_MIGRATING;
        }
        return PlayerCommandStatus.AGENT_MISSING;
    }

    private void audit(
            PlayerCommand command,
            PlayerCommandStatus status,
            PlayerCommandAuditOutcome outcome,
            long configVersion,
            Duration elapsed,
            String reason
    ) {
        auditSink.record(new PlayerCommandAuditRecord(
                command.playerId(),
                command.sessionId(),
                command.sessionEpoch(),
                command.sequence(),
                command.operation(),
                status,
                outcome,
                configVersion,
                elapsed,
                clock.instant(),
                reason
        ));
    }

    private Duration elapsed(Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, clock.instant());
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }
}
