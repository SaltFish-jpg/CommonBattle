package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.agent.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketAgentAdmissionControllerTest {
    @Test
    void rejectsWhenAgentOperationBucketIsEmpty() {
        MutableClock clock = new MutableClock();
        TokenBucketAgentAdmissionController admissions = new TokenBucketAgentAdmissionController(
                new AgentRateLimitPolicy(2, 1, Duration.ofSeconds(1)),
                clock
        );
        AgentIdentity player = AgentIdentity.player(10001L);

        assertTrue(admissions.admit(player, "bag.use").accepted());
        assertTrue(admissions.admit(player, "bag.use").accepted());
        AdmissionDecision rejected = admissions.admit(player, "bag.use");

        assertFalse(rejected.accepted());
        assertTrue(rejected.retryAfter().toMillis() > 0);
    }

    @Test
    void refillsTokensAfterInterval() {
        MutableClock clock = new MutableClock();
        TokenBucketAgentAdmissionController admissions = new TokenBucketAgentAdmissionController(
                new AgentRateLimitPolicy(1, 1, Duration.ofSeconds(1)),
                clock
        );
        AgentIdentity player = AgentIdentity.player(10001L);
        admissions.admit(player, "activity.claim");
        assertFalse(admissions.admit(player, "activity.claim").accepted());

        clock.advance(Duration.ofSeconds(1));

        assertTrue(admissions.admit(player, "activity.claim").accepted());
    }

    @Test
    void bucketsAreSeparatedByAgentAndOperation() {
        MutableClock clock = new MutableClock();
        TokenBucketAgentAdmissionController admissions = new TokenBucketAgentAdmissionController(
                new AgentRateLimitPolicy(1, 1, Duration.ofSeconds(1)),
                clock
        );

        assertTrue(admissions.admit(AgentIdentity.player(10001L), "bag.use").accepted());
        assertTrue(admissions.admit(AgentIdentity.player(10002L), "bag.use").accepted());
        assertTrue(admissions.admit(AgentIdentity.player(10001L), "activity.claim").accepted());
        assertFalse(admissions.admit(AgentIdentity.player(10001L), "bag.use").accepted());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
