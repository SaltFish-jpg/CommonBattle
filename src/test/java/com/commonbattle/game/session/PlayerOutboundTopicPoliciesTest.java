package com.commonbattle.game.session;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerOutboundTopicPoliciesTest {
    @Test
    void unknownTopicFallsBackToReliablePolicy() {
        PlayerOutboundTopicPolicies policies = PlayerOutboundTopicPolicies.gameDefaults();

        PlayerOutboundTopicPolicy policy = policies.resolve("system.notice");

        assertEquals(PlayerOutboundDeliveryMode.RELIABLE, policy.mode());
        assertEquals("", policy.coalesceKey());
    }

    @Test
    void gameDefaultTopicsDeclareDeliverySemantics() {
        PlayerOutboundTopicPolicies policies = PlayerOutboundTopicPolicies.gameDefaults();

        assertEquals(
                new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.COALESCING, "bag"),
                policies.resolve(PlayerOutboundTopicPolicies.BAG_SNAPSHOT)
        );
        assertEquals(
                new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.COALESCING, "activity"),
                policies.resolve(PlayerOutboundTopicPolicies.ACTIVITY_PROGRESS)
        );
        assertEquals(
                new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.COALESCING, "scene"),
                policies.resolve(PlayerOutboundTopicPolicies.SCENE_SNAPSHOT)
        );
        assertEquals(
                new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.BEST_EFFORT, ""),
                policies.resolve(PlayerOutboundTopicPolicies.COMBAT_FLOAT_TEXT)
        );
        assertEquals(
                new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.COALESCING, "shop"),
                policies.resolve(PlayerOutboundTopicPolicies.SHOP_SNAPSHOT)
        );
        assertEquals(
                new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.COALESCING, "growth"),
                policies.resolve(PlayerOutboundTopicPolicies.GROWTH_SNAPSHOT)
        );
        assertEquals(
                new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.COALESCING, "battle"),
                policies.resolve(PlayerOutboundTopicPolicies.BATTLE_SNAPSHOT)
        );
        assertEquals(
                new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.COALESCING, "task"),
                policies.resolve(PlayerOutboundTopicPolicies.TASK_PROGRESS)
        );
        assertEquals(
                new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.COALESCING, "achievement"),
                policies.resolve(PlayerOutboundTopicPolicies.ACHIEVEMENT_PROGRESS)
        );
    }

    @Test
    void envelopeAppliesRegisteredTopicPolicy() {
        PlayerOutboundTopicPolicies policies = new PlayerOutboundTopicPolicies()
                .register("bag.snapshot", PlayerOutboundTopicPolicy.coalescing("bag"));

        PlayerOutboundEnvelope envelope = policies.envelope(Set.of(10001L), "bag.snapshot", "rev-2");

        assertEquals(Set.of(10001L), envelope.recipients());
        assertEquals("bag.snapshot", envelope.topic());
        assertEquals("rev-2", envelope.payload());
        assertEquals(PlayerOutboundDeliveryMode.COALESCING, envelope.mode());
        assertEquals("bag", envelope.coalesceKey());
    }

    @Test
    void rejectsInvalidTopicAndCoalescingKey() {
        PlayerOutboundTopicPolicies policies = new PlayerOutboundTopicPolicies();

        assertThrows(IllegalArgumentException.class, () -> policies.register(" ", PlayerOutboundTopicPolicy.reliable()));
        assertThrows(IllegalArgumentException.class, () -> PlayerOutboundTopicPolicy.coalescing(" "));
    }
}
