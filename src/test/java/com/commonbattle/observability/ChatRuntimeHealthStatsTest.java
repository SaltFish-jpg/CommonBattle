package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.chat.ChatRuntimeView;
import com.commonbattle.game.chat.ChatPersistenceStats;
import com.commonbattle.game.chat.ChatServiceStats;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRuntimeHealthStatsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void snapshotFormatsChatRuntimeStats() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register((ChatRuntimeView) () -> new ChatServiceStats(
                3, 2, 4, 1, 9, 1, 2, 40, 5, 20, 3, 1, 6, 4,
                7, 5, 2, 1, 0,
                11, 8, 3, 1, 0
        ).withPersistenceStats(new ChatPersistenceStats(
                2, 1, 1, 0, 3, 4, 5, 0, 0, 0, 0, 0
        )));
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.CHAT, "r1", "chat-1"),
                        actors,
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

        assertEquals(3, snapshot.chatRuntimes().activeChannels());
        assertEquals(2, snapshot.chatRuntimes().activeDirectSessions());
        assertEquals(10, snapshot.chatRuntimes().allianceRemovedMembers());
        assertEquals(6, snapshot.chatRuntimes().allianceEventRemovedMembers());
        assertEquals(4, snapshot.chatRuntimes().allianceSnapshotRemovedMembers());
        assertEquals(7, snapshot.chatRuntimes().asyncMessageChecks());
        assertEquals(5, snapshot.chatRuntimes().asyncMessageSentDecisions());
        assertEquals(2, snapshot.chatRuntimes().asyncMessageRejectedDecisions());
        assertEquals(1, snapshot.chatRuntimes().asyncMessageBackpressuredDecisions());
        assertEquals(11, snapshot.chatRuntimes().asyncAccessChecks());
        assertEquals(8, snapshot.chatRuntimes().asyncAccessAllowedDecisions());
        assertEquals(3, snapshot.chatRuntimes().asyncAccessRejectedDecisions());
        assertEquals(1, snapshot.chatRuntimes().asyncAccessBackpressuredDecisions());
        assertEquals(2, snapshot.chatRuntimes().persistence().agents());
        assertEquals(3, snapshot.chatRuntimes().persistence().pendingCommands());
        assertTrue(json.contains("\"chatRuntimes\":{\"runtimeCount\":1,\"activeChannels\":3"));
        assertTrue(json.contains("\"allianceRemovedMembers\":10"));
        assertTrue(json.contains("\"asyncMessageChecks\":7"));
        assertTrue(json.contains("\"asyncAccessChecks\":11"));
        assertTrue(json.contains("\"persistence\":{\"agents\":2,\"recoveringAgents\":1"));
        assertTrue(metrics.contains("commonbattle_chat_active_channels 3"));
        assertTrue(metrics.contains("commonbattle_chat_dropped_history_messages_total 5"));
        assertTrue(metrics.contains("commonbattle_chat_dropped_delivery_recipients_total 3"));
        assertTrue(metrics.contains("commonbattle_chat_alliance_event_removed_members_total 6"));
        assertTrue(metrics.contains("commonbattle_chat_alliance_snapshot_removed_members_total 4"));
        assertTrue(metrics.contains("commonbattle_chat_async_message_checks_total 7"));
        assertTrue(metrics.contains("commonbattle_chat_async_message_backpressured_decisions_total 1"));
        assertTrue(metrics.contains("commonbattle_chat_async_access_checks_total 11"));
        assertTrue(metrics.contains("commonbattle_chat_async_access_backpressured_decisions_total 1"));
        assertTrue(metrics.contains("commonbattle_chat_persistence_pending_commands 3"));
        assertTrue(metrics.contains("commonbattle_chat_persistence_completed_saves_total 5"));
    }

    @Test
    void asyncChatCallbackDeliveryFailuresDegradeHealth() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register((ChatRuntimeView) () -> new ChatServiceStats(
                1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                1, 1, 0, 0, 1,
                0, 0, 0, 0, 0
        ));
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.CHAT, "r1", "chat-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.chatRuntimes().asyncMessageCallbackDeliveryFailures());
    }

    @Test
    void asyncChatAccessCallbackDeliveryFailuresDegradeHealth() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register((ChatRuntimeView) () -> new ChatServiceStats(
                1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                1, 1, 0, 0, 1
        ));
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.CHAT, "r1", "chat-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.chatRuntimes().asyncAccessCallbackDeliveryFailures());
    }

    @Test
    void chatPersistenceFailureDegradesHealth() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register((ChatRuntimeView) () -> ChatServiceStats.empty().withPersistenceStats(
                new ChatPersistenceStats(1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0)
        ));
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.CHAT, "r1", "chat-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.chatRuntimes().persistence().saveFailures());
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
