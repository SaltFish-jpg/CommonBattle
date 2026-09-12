package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.chat.ChatRuntimeView;
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
        registry.register((ChatRuntimeView) () -> new ChatServiceStats(3, 2, 4, 1, 9, 1, 2, 40, 5, 20, 3, 1));
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
        assertTrue(json.contains("\"chatRuntimes\":{\"runtimeCount\":1,\"activeChannels\":3"));
        assertTrue(metrics.contains("commonbattle_chat_active_channels 3"));
        assertTrue(metrics.contains("commonbattle_chat_dropped_history_messages_total 5"));
        assertTrue(metrics.contains("commonbattle_chat_dropped_delivery_recipients_total 3"));
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
