package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundEnvelope;
import com.commonbattle.game.session.PlayerOutboundTopicDeliveryStats;
import com.commonbattle.game.session.PlayerSession;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerOutboundDeliveryHealthStatsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void snapshotFormatsPlayerOutboundDeliveryStats() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerOutboundDeliveryHub hub = new PlayerOutboundDeliveryHub(
                sessions,
                CLOCK,
                4,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerSession online = sessions.bind(10001L, "client-1");
        hub.connect(online, ignored -> true);
        hub.deliver(new PlayerOutboundEnvelope(Set.of(10001L, 10002L), "chat.delivery", "hello"));
        registry.register(hub);

        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                new ActorSystem(new InlineExecutor(), 64),
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        new ActorSystem(Runnable::run, 64),
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(1, snapshot.playerOutboundDeliveries().runtimeCount());
        assertEquals(1, snapshot.playerOutboundDeliveries().activeConnections());
        assertEquals(1, snapshot.playerOutboundDeliveries().offlinePlayers());
        assertEquals(1, snapshot.playerOutboundDeliveries().pendingOfflineMessages());
        assertEquals(1, snapshot.playerOutboundDeliveries().pendingAckPlayers());
        assertEquals(1, snapshot.playerOutboundDeliveries().pendingAckMessages());
        assertEquals(0, snapshot.playerOutboundDeliveries().oldestPendingAckAgeMillis());
        assertEquals(1, snapshot.playerOutboundDeliveries().onlineDeliveries());
        assertEquals(1, snapshot.playerOutboundDeliveries().offlineQueuedDeliveries());
        assertEquals(0, snapshot.playerOutboundDeliveries().coalescedDeliveries());
        PlayerOutboundTopicDeliveryStats topic = snapshot.playerOutboundDeliveries().topics().get("chat.delivery");
        assertEquals(1, topic.onlineDeliveries());
        assertEquals(1, topic.offlineQueuedDeliveries());
        assertEquals(1, topic.pendingOfflineMessages());
        assertEquals(1, topic.pendingAckMessages());
        assertTrue(json.contains("\"playerOutboundDeliveries\":{\"runtimeCount\":1,\"activeConnections\":1"));
        assertTrue(json.contains("\"topics\":{\"chat.delivery\""));
        assertTrue(metrics.contains("commonbattle_player_outbound_online_deliveries_total 1"));
        assertTrue(metrics.contains("commonbattle_player_outbound_pending_offline_messages 1"));
        assertTrue(metrics.contains("commonbattle_player_outbound_pending_ack_players 1"));
        assertTrue(metrics.contains("commonbattle_player_outbound_pending_ack_messages 1"));
        assertTrue(metrics.contains("commonbattle_player_outbound_oldest_pending_ack_age_millis 0"));
        assertTrue(metrics.contains("commonbattle_player_outbound_coalesced_deliveries_total 0"));
        assertTrue(metrics.contains("commonbattle_player_outbound_topic_online_deliveries_total{topic=\"chat.delivery\"} 1"));
        assertTrue(metrics.contains("commonbattle_player_outbound_topic_pending_ack_messages{topic=\"chat.delivery\"} 1"));
    }

    private static final class InlineExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
            command.run();
        }
    }
}
