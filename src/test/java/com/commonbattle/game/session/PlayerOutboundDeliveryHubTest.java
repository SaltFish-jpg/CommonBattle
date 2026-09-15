package com.commonbattle.game.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerOutboundDeliveryHubTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void currentOnlineSessionReceivesMessageImmediately() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession session = sessions.bind(10001L, "client-1");
        List<PlayerOutboundMessage> written = new ArrayList<>();

        assertTrue(hub.connect(session, message -> {
            written.add(message);
            return true;
        }));
        PlayerOutboundDeliveryResult result = hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "bag.changed", "payload"));

        assertEquals(new PlayerOutboundDeliveryResult(1, 0, 0, 0), result);
        assertEquals(1, written.size());
        assertEquals("bag.changed", written.getFirst().topic());
        assertEquals("payload", written.getFirst().payload());
        assertEquals(0, hub.pendingOffline(10001L).size());
        assertEquals(1, hub.pendingAck(10001L).size());
        assertEquals(1, hub.deliveryStats().activeConnections());
    }

    @Test
    void ackRemovesConfirmedOnlineMessagesFromPendingWindow() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession session = sessions.bind(10001L, "client-1");
        hub.connect(session, ignored -> true);
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "mail.notice", "one"));
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "mail.notice", "two"));

        long sequence = hub.pendingAck(10001L).getLast().sequence();
        long acknowledged = hub.acknowledge(session, sequence);

        assertEquals(2, acknowledged);
        assertEquals(0, hub.pendingAck(10001L).size());
        assertEquals(2, hub.deliveryStats().ackedDeliveries());
        assertEquals(2, hub.deliveryStats().topics().get("mail.notice").ackedDeliveries());
        assertEquals(0, hub.deliveryStats().topics().get("mail.notice").pendingAckMessages());
    }

    @Test
    void ackStatusDetectsPendingWindowLimitAndTimeout() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(clock);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                clock,
                8,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession session = sessions.bind(10001L, "client-1");
        hub.connect(session, ignored -> true);
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "one"));
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "two"));

        PlayerOutboundAckStatus overLimit = hub.ackStatus(session, 2, Duration.ZERO);
        clock.advance(Duration.ofSeconds(31));
        PlayerOutboundAckStatus timedOut = hub.ackStatus(session, 10, Duration.ofSeconds(30));

        assertTrue(overLimit.overLimit());
        assertFalse(overLimit.timedOut());
        assertTrue(overLimit.slow());
        assertFalse(timedOut.overLimit());
        assertTrue(timedOut.timedOut());
        assertEquals(31_000, timedOut.oldestPendingAgeMillis());
        assertEquals(1, hub.deliveryStats().pendingAckPlayers());
        assertEquals(31_000, hub.deliveryStats().oldestPendingAckAgeMillis());
    }

    @Test
    void pendingAckWindowUsesDedicatedCapacity() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                1,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession session = sessions.bind(10001L, "client-1");
        hub.connect(session, ignored -> true);

        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "one"));
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "two"));

        assertEquals(List.of("two"), hub.pendingAck(10001L).stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(1, hub.deliveryStats().droppedDeliveries());
        assertEquals(1, hub.deliveryStats().topics().get("system.notice").droppedDeliveries());
        assertEquals(1, hub.deliveryStats().topics().get("system.notice").pendingAckMessages());
    }

    @Test
    void bestEffortOnlineDeliveryDoesNotEnterAckWindow() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession session = sessions.bind(10001L, "client-1");
        List<PlayerOutboundMessage> written = new ArrayList<>();
        hub.connect(session, message -> {
            written.add(message);
            return true;
        });

        PlayerOutboundDeliveryResult result = hub.deliver(
                PlayerOutboundEnvelope.bestEffort(Set.of(10001L), "float.text", "miss")
        );

        assertEquals(new PlayerOutboundDeliveryResult(1, 0, 0, 0), result);
        assertEquals(List.of("miss"), written.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(0, hub.pendingAck(10001L).size());
        assertEquals(1, hub.deliveryStats().topics().get("float.text").onlineDeliveries());
        assertEquals(0, hub.deliveryStats().topics().get("float.text").pendingAckMessages());
    }

    @Test
    void bestEffortOfflineDeliveryIsDroppedWithoutOfflineQueue() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );

        PlayerOutboundDeliveryResult result = hub.deliver(
                PlayerOutboundEnvelope.bestEffort(Set.of(10001L), "float.text", "miss")
        );

        assertEquals(new PlayerOutboundDeliveryResult(0, 0, 1, 0), result);
        assertEquals(0, hub.pendingOffline(10001L).size());
        assertEquals(1, hub.deliveryStats().droppedDeliveries());
        assertEquals(1, hub.deliveryStats().topics().get("float.text").droppedDeliveries());
    }

    @Test
    void coalescingOfflineDeliveryKeepsNewestSnapshotPerKey() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );

        hub.deliver(PlayerOutboundEnvelope.coalescing(Set.of(10001L), "bag.snapshot", "rev-1", "bag"));
        hub.deliver(PlayerOutboundEnvelope.coalescing(Set.of(10001L), "bag.snapshot", "rev-2", "bag"));

        assertEquals(List.of("rev-2"), hub.pendingOffline(10001L).stream()
                .map(PlayerOutboundMessage::payload)
                .toList());
        assertEquals(1, hub.deliveryStats().coalescedDeliveries());
        PlayerOutboundTopicDeliveryStats topic = hub.deliveryStats().topics().get("bag.snapshot");
        assertEquals(2, topic.offlineQueuedDeliveries());
        assertEquals(1, topic.coalescedDeliveries());
        assertEquals(1, topic.pendingOfflineMessages());
    }

    @Test
    void coalescingPendingAckWindowReplaysOnlyNewestSnapshot() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession first = sessions.bind(10001L, "client-1");
        hub.connect(first, ignored -> true);

        hub.deliver(PlayerOutboundEnvelope.coalescing(Set.of(10001L), "scene.snapshot", "pos-1", "scene:1"));
        hub.deliver(PlayerOutboundEnvelope.coalescing(Set.of(10001L), "scene.snapshot", "pos-2", "scene:1"));
        hub.disconnect(first);
        sessions.unbind(first);
        PlayerSession second = sessions.bind(10001L, "client-2");
        List<PlayerOutboundMessage> replayed = new ArrayList<>();
        hub.connect(second, message -> {
            replayed.add(message);
            return true;
        });

        hub.flushPendingAck(second, 16);

        assertEquals(List.of("pos-2"), hub.pendingAck(10001L).stream()
                .map(PlayerOutboundMessage::payload)
                .toList());
        assertEquals(List.of("pos-2"), replayed.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(1, hub.deliveryStats().coalescedDeliveries());
    }

    @Test
    void reconnectReplaysUnackedMessagesBeforeOfflineMessages() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession first = sessions.bind(10001L, "client-1");
        List<PlayerOutboundMessage> firstWriter = new ArrayList<>();
        hub.connect(first, message -> {
            firstWriter.add(message);
            return true;
        });
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "unacked-online"));
        hub.disconnect(first);
        sessions.unbind(first);
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "offline-after-drop"));

        PlayerSession second = sessions.bind(10001L, "client-2");
        List<PlayerOutboundMessage> secondWriter = new ArrayList<>();
        hub.connect(second, message -> {
            secondWriter.add(message);
            return true;
        });
        hub.flushPendingAck(second, 16);
        hub.flushOffline(second, 16);

        assertEquals(List.of("unacked-online"), firstWriter.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(List.of("unacked-online", "offline-after-drop"),
                secondWriter.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(2, hub.pendingAck(10001L).size());
        assertEquals(0, hub.pendingOffline(10001L).size());
        PlayerOutboundTopicDeliveryStats topic = hub.deliveryStats().topics().get("system.notice");
        assertEquals(3, topic.onlineDeliveries());
        assertEquals(1, topic.offlineQueuedDeliveries());
        assertEquals(2, topic.pendingAckMessages());
    }

    @Test
    void staleSessionCannotConnectAndDoesNotReceiveNewMessages() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession stale = sessions.bind(10001L, "old-client");
        sessions.bind(10001L, "new-client");

        assertFalse(hub.connect(stale, ignored -> true));
        PlayerOutboundDeliveryResult result = hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "chat.delivery", "hello"));

        assertEquals(new PlayerOutboundDeliveryResult(0, 1, 0, 0), result);
        assertEquals(List.of("hello"), hub.pendingOffline(10001L).stream().map(PlayerOutboundMessage::payload).toList());
    }

    @Test
    void offlineBufferDropsOldestMessageWhenFull() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                2,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );

        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "chat.delivery", "one"));
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "chat.delivery", "two"));
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "chat.delivery", "three"));

        assertEquals(List.of("two", "three"), hub.pendingOffline(10001L).stream()
                .map(PlayerOutboundMessage::payload)
                .toList());
        assertEquals(3, hub.deliveryStats().offlineQueuedDeliveries());
        assertEquals(1, hub.deliveryStats().droppedDeliveries());
    }

    @Test
    void onlineWriteFailureFallsBackToOfflineBufferAndCanFlushLater() {
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                8,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession session = sessions.bind(10001L, "client-1");
        hub.connect(session, ignored -> false);

        PlayerOutboundDeliveryResult failed = hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "chat.delivery", "hello"));

        assertEquals(new PlayerOutboundDeliveryResult(0, 1, 0, 1), failed);
        assertEquals(1, hub.pendingOffline(10001L).size());

        List<PlayerOutboundMessage> written = new ArrayList<>();
        hub.connect(session, message -> {
            written.add(message);
            return true;
        });
        PlayerOutboundDeliveryResult flushed = hub.flushOffline(session, 16);

        assertEquals(new PlayerOutboundDeliveryResult(1, 0, 0, 0), flushed);
        assertEquals(List.of("hello"), written.stream().map(PlayerOutboundMessage::payload).toList());
        assertEquals(0, hub.pendingOffline(10001L).size());
        assertEquals(1, hub.deliveryStats().failedOnlineDeliveries());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
