package com.commonbattle.game.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubscriptionCheckpointTest {
    @Test
    void detectsDuplicateAndRevisionGap() {
        SubscriptionCheckpoint checkpoint = new SubscriptionCheckpoint();
        TestEvent first = new TestEvent("alliance:1", 1);
        TestEvent duplicate = new TestEvent("alliance:1", 1);
        TestEvent gap = new TestEvent("alliance:1", 3);

        assertEquals(SubscriptionDecision.APPLY, checkpoint.inspect(first));
        checkpoint.markApplied(first);
        assertEquals(SubscriptionDecision.DUPLICATE_OR_OLD, checkpoint.inspect(duplicate));
        assertEquals(SubscriptionDecision.GAP, checkpoint.inspect(gap));
    }

    private record TestEvent(String ownerKey, long revision) implements VersionedEvent {
        @Override
        public String topic() {
            return "test";
        }
    }
}
