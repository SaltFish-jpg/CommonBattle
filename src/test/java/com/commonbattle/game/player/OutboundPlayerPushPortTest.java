package com.commonbattle.game.player;

import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundDeliveryMode;
import com.commonbattle.game.session.PlayerOutboundMessage;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboundPlayerPushPortTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void pushUsesTopicPolicyForCoalescingSnapshots() {
        PlayerOutboundDeliveryHub hub = hub();
        PlayerPushPort pushes = new OutboundPlayerPushPort(hub, PlayerOutboundTopicPolicies.gameDefaults());

        pushes.push(10001L, PlayerOutboundTopicPolicies.BAG_SNAPSHOT, "rev-1");
        pushes.push(10001L, PlayerOutboundTopicPolicies.BAG_SNAPSHOT, "rev-2");

        List<PlayerOutboundMessage> pending = hub.pendingOffline(10001L);
        assertEquals(1, pending.size());
        assertEquals("rev-2", pending.getFirst().payload());
        assertEquals(PlayerOutboundDeliveryMode.COALESCING, pending.getFirst().mode());
        assertEquals("bag", pending.getFirst().coalesceKey());
    }

    @Test
    void pushDropsBestEffortMessageForOfflinePlayer() {
        PlayerOutboundDeliveryHub hub = hub();
        PlayerPushPort pushes = new OutboundPlayerPushPort(hub, PlayerOutboundTopicPolicies.gameDefaults());

        pushes.push(10001L, PlayerOutboundTopicPolicies.COMBAT_FLOAT_TEXT, "miss");

        assertEquals(List.of(), hub.pendingOffline(10001L));
        assertEquals(1, hub.deliveryStats().droppedDeliveries());
    }

    private static PlayerOutboundDeliveryHub hub() {
        return new PlayerOutboundDeliveryHub(
                new InMemoryPlayerSessionRegistry(CLOCK),
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
    }
}
