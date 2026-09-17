package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.agent.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorMailboxPressureAdmissionControllerTest {
    @Test
    void rejectsTargetWhenResolvedMailboxIsAlreadyHot() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        actors.send(actors.actor("player-10001"),
                ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND, ignored -> {
                }));
        ActorMailboxPressureAdmissionController controller = new ActorMailboxPressureAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                new ActorMailboxPressurePolicy(true, 1, 0, Duration.ofMillis(50))
        );

        AdmissionDecision decision = controller.admit(AgentIdentity.player(10001L), "bag.use");

        assertFalse(decision.accepted());
        assertEquals(ActorMailboxPressureAdmissionController.TARGET_PRESSURE_REASON, decision.reason());
        assertEquals(Duration.ofMillis(50), decision.retryAfter());
        assertEquals(1, actors.stats().queuedTasks());
        ActorMailboxPressureStats stats = controller.mailboxPressureStats();
        assertEquals(1, stats.admissions());
        assertEquals(0, stats.accepted());
        assertEquals(1, stats.pressureRejected());
        assertEquals(1, stats.targetPressureRejected());
        assertEquals(1, stats.rejectedByTargetType().get(AgentIdentity.PLAYER));
        assertEquals(1, stats.rejectedByActorGroup().get("player"));
        assertEquals(1, stats.rejectedByReason().get(ActorMailboxPressureAdmissionController.TARGET_PRESSURE_REASON));
    }

    @Test
    void rejectsGroupWhenBusinessGroupIsHot() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        actors.send(actors.actor("player-10001"), ignored -> {
        });
        actors.send(actors.actor("player-10002"), ignored -> {
        });
        ActorMailboxPressureAdmissionController controller = new ActorMailboxPressureAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                new ActorMailboxPressurePolicy(true, 0, 2, Duration.ofMillis(20))
        );

        AdmissionDecision decision = controller.admit(AgentIdentity.player(10003L), "bag.use");

        assertFalse(decision.accepted());
        assertEquals(ActorMailboxPressureAdmissionController.GROUP_PRESSURE_REASON, decision.reason());
    }

    @Test
    void keepsDelegateRejectionAndAllowsWhenDisabled() {
        ActorSystem actors = new ActorSystem(new RecordingExecutor(), 64);
        ActorMailboxPressureAdmissionController rejecting = new ActorMailboxPressureAdmissionController(
                (target, operation) -> AdmissionDecision.reject("rate_limited", Duration.ofMillis(100)),
                actors,
                new ActorMailboxPressurePolicy(true, 1, 1, Duration.ofMillis(20))
        );
        ActorMailboxPressureAdmissionController disabled = new ActorMailboxPressureAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                ActorMailboxPressurePolicy.disabled()
        );

        assertEquals("rate_limited", rejecting.admit(AgentIdentity.player(10001L), "bag.use").reason());
        assertTrue(disabled.admit(AgentIdentity.player(10001L), "bag.use").accepted());
        assertEquals(1, rejecting.mailboxPressureStats().delegateRejected());
        assertEquals(1, disabled.mailboxPressureStats().accepted());
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }
    }
}
