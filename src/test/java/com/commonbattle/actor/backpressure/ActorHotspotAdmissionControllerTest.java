package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSlowTask;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.observability.ActorHotspotPolicy;
import com.commonbattle.observability.InMemoryActorSlowTaskLog;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorHotspotAdmissionControllerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsTargetWhenHotspotActionRequiresThrottle() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        actors.send(actors.actor("player-10001"), ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND,
                ignored -> {
                }));
        actors.send(actors.actor("player-10001"), ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK,
                ignored -> {
                }));
        ActorHotspotAdmissionController controller = new ActorHotspotAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                List.of(),
                new ActorHotspotPolicy(1, 2, 5, 2, 5, 100, 500),
                Duration.ofMillis(75)
        );

        AdmissionDecision decision = controller.admit(AgentIdentity.player(10001L), "bag.use");

        assertFalse(decision.accepted());
        assertEquals(ActorHotspotAdmissionController.THROTTLE_REASON, decision.reason());
        assertEquals(Duration.ofMillis(75), decision.retryAfter());
        ActorHotspotAdmissionStats stats = controller.hotspotAdmissionStats();
        assertEquals(1, stats.admissions());
        assertEquals(1, stats.hotspotRejected());
        assertEquals(1, stats.throttleRejected());
        assertEquals(1, stats.rejectedByTargetType().get(AgentIdentity.PLAYER));
        assertEquals(1, stats.rejectedByActorGroup().get("player"));
        assertEquals(1, stats.rejectedByReason().get(ActorHotspotAdmissionController.THROTTLE_REASON));
    }

    @Test
    void rejectsMigrationCandidateFromSlowTaskSignal() {
        ActorSystem actors = new ActorSystem(new RecordingExecutor(), 64);
        InMemoryActorSlowTaskLog slowTasks = new InMemoryActorSlowTaskLog(8, CLOCK);
        slowTasks.accept(new ActorSlowTask(new ActorRef("player-10002"), ActorTaskCategory.PLAYER_COMMAND,
                Duration.ofMillis(600), Duration.ofMillis(10)));
        ActorHotspotAdmissionController controller = new ActorHotspotAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                List.of(slowTasks),
                new ActorHotspotPolicy(1, 2, 5, 2, 5, 100, 500),
                Duration.ofMillis(100)
        );

        AdmissionDecision decision = controller.admit(AgentIdentity.player(10002L), "shop.buy");

        assertFalse(decision.accepted());
        assertEquals(ActorHotspotAdmissionController.MIGRATION_CANDIDATE_REASON, decision.reason());
        ActorHotspotAdmissionStats stats = controller.hotspotAdmissionStats();
        assertEquals(1, stats.migrationCandidateRejected());
        assertEquals(1, stats.rejectedByReason().get(ActorHotspotAdmissionController.MIGRATION_CANDIDATE_REASON));
    }

    @Test
    void keepsDelegateRejectionAndAllowsObserveOnlyHotspot() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        actors.send(actors.actor("player-10001"), ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND,
                ignored -> {
                }));
        ActorHotspotAdmissionController rejecting = new ActorHotspotAdmissionController(
                (target, operation) -> AdmissionDecision.reject("rate_limited", Duration.ofMillis(50)),
                actors,
                List.of(),
                new ActorHotspotPolicy(1, 2, 5, 2, 5, 100, 500),
                Duration.ofMillis(100)
        );
        ActorHotspotAdmissionController observeOnly = new ActorHotspotAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                List.of(),
                new ActorHotspotPolicy(1, 2, 5, 2, 5, 100, 500),
                Duration.ofMillis(100)
        );

        AdmissionDecision rejected = rejecting.admit(AgentIdentity.player(10001L), "bag.use");
        AdmissionDecision accepted = observeOnly.admit(AgentIdentity.player(10001L), "bag.use");

        assertEquals("rate_limited", rejected.reason());
        assertTrue(accepted.accepted());
        assertEquals(1, rejecting.hotspotAdmissionStats().delegateRejected());
        assertEquals(1, observeOnly.hotspotAdmissionStats().accepted());
    }

    @Test
    void manualExemptSkipsOnlyHotspotDecisionUntilExpired() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-02T00:00:00Z"));
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        actors.send(actors.actor("player-10001"), ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND,
                ignored -> {
                }));
        actors.send(actors.actor("player-10001"), ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK,
                ignored -> {
                }));
        ActorHotspotAdmissionController controller = new ActorHotspotAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                List.of(),
                new ActorHotspotPolicy(1, 2, 5, 2, 5, 100, 500),
                Duration.ofMillis(100),
                target -> "player-" + target.key(),
                clock
        );
        controller.setHotspotOverride("player-10001", ActorHotspotOverrideMode.EXEMPT,
                Duration.ofSeconds(1), "activity hotfix");

        AdmissionDecision exempted = controller.admit(AgentIdentity.player(10001L), "bag.use");
        clock.advance(Duration.ofSeconds(2));
        AdmissionDecision expired = controller.admit(AgentIdentity.player(10001L), "bag.use");

        assertTrue(exempted.accepted());
        assertFalse(expired.accepted());
        assertEquals(ActorHotspotAdmissionController.THROTTLE_REASON, expired.reason());
        assertTrue(controller.hotspotOverrides().isEmpty());
    }

    @Test
    void manualMigrationCandidateCanRejectColdActor() {
        ActorSystem actors = new ActorSystem(new RecordingExecutor(), 64);
        ActorHotspotAdmissionController controller = new ActorHotspotAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                List.of(),
                new ActorHotspotPolicy(1, 2, 5, 2, 5, 100, 500),
                Duration.ofMillis(80),
                target -> "player-" + target.key(),
                CLOCK
        );
        controller.setHotspotOverride("player-10003", ActorHotspotOverrideMode.MIGRATION_CANDIDATE,
                Duration.ofSeconds(30), "manual move");

        AdmissionDecision decision = controller.admit(AgentIdentity.player(10003L), "shop.buy");

        assertFalse(decision.accepted());
        assertEquals(ActorHotspotAdmissionController.MANUAL_MIGRATION_CANDIDATE_REASON, decision.reason());
        ActorHotspotAdmissionStats stats = controller.hotspotAdmissionStats();
        assertEquals(1, stats.hotspotRejected());
        assertEquals(1, stats.migrationCandidateRejected());
        assertEquals(1, stats.rejectedByReason()
                .get(ActorHotspotAdmissionController.MANUAL_MIGRATION_CANDIDATE_REASON));
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
