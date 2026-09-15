package com.commonbattle.game.chat;

import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundDeliveryMode;
import com.commonbattle.game.session.PlayerOutboundMessage;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;
import com.commonbattle.game.session.PlayerOutboundTopicPolicy;
import com.commonbattle.game.session.PlayerSession;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerOutboundChatDeliverySinkTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void chatDeliveryUsesGenericPlayerOutboundHub() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession online = sessions.bind(10001L, "client-1");
        List<PlayerOutboundMessage> written = new ArrayList<>();
        hub.connect(online, message -> {
            written.add(message);
            return true;
        });
        ChatDelivery delivery = new ChatDelivery("world:main:0", 10002L, "player-10002", "hello", 1, CLOCK.instant());
        PlayerOutboundChatDeliverySink sink = new PlayerOutboundChatDeliverySink(hub);

        ChatDeliveryResult result = sink.deliver(new ChatDeliveryEnvelope(
                delivery.channelId(),
                Set.of(10001L, 10003L),
                delivery
        ));

        assertEquals(new ChatDeliveryResult(2, 0, 0), result);
        assertEquals(PlayerOutboundChatDeliverySink.TOPIC, written.getFirst().topic());
        assertEquals(delivery, written.getFirst().payload());
        assertEquals(List.of(delivery), hub.pendingOffline(10003L).stream()
                .map(PlayerOutboundMessage::payload)
                .toList());
    }

    @Test
    void chatDeliveryCanBeDowngradedByTopicPolicy() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        ChatDelivery delivery = new ChatDelivery("world:main:0", 10002L, "player-10002", "hello", 1, CLOCK.instant());
        PlayerOutboundTopicPolicies policies = new PlayerOutboundTopicPolicies()
                .register(PlayerOutboundChatDeliverySink.TOPIC, PlayerOutboundTopicPolicy.bestEffort());
        PlayerOutboundChatDeliverySink sink = new PlayerOutboundChatDeliverySink(hub, policies);

        ChatDeliveryResult result = sink.deliver(new ChatDeliveryEnvelope(
                delivery.channelId(),
                Set.of(10001L),
                delivery
        ));

        assertEquals(new ChatDeliveryResult(0, 1, 0), result);
        assertEquals(List.of(), hub.pendingOffline(10001L));
        assertEquals(PlayerOutboundDeliveryMode.BEST_EFFORT, policies.resolve(PlayerOutboundChatDeliverySink.TOPIC).mode());
    }
}
