package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.player.NettyPlayerGatewayStats;
import com.commonbattle.game.player.PlayerGatewayView;
import com.commonbattle.game.session.PlayerClientErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerGatewayHealthStatsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void snapshotFormatsPlayerGatewayStats() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        PlayerGatewayView first = () -> new NettyPlayerGatewayStats(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, Map.of(
                PlayerClientErrorCode.NOT_LOGGED_IN, 2L,
                PlayerClientErrorCode.SESSION_EXPIRED, 5L
        ));
        PlayerGatewayView second = () -> new NettyPlayerGatewayStats(14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3,
                2, 1, 1, 1, 1, Map.of(
                PlayerClientErrorCode.NOT_LOGGED_IN, 3L,
                PlayerClientErrorCode.COMMAND_RATE_LIMITED, 7L
        ));
        registry.register(java.util.List.of(first, second));

        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                new ActorSystem(Runnable::run, 64),
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

        assertEquals(2, snapshot.playerGateways().gatewayCount());
        assertEquals(15, snapshot.playerGateways().acceptedLogins());
        assertEquals(15, snapshot.playerGateways().failedLogins());
        assertEquals(15, snapshot.playerGateways().authRejectedLogins());
        assertEquals(15, snapshot.playerGateways().duplicateRejectedLogins());
        assertEquals(15, snapshot.playerGateways().kickedConnections());
        assertEquals(15, snapshot.playerGateways().acceptedCommands());
        assertEquals(15, snapshot.playerGateways().rejectedCommands());
        assertEquals(15, snapshot.playerGateways().rateLimitedCommands());
        assertEquals(15, snapshot.playerGateways().acceptedHeartbeats());
        assertEquals(15, snapshot.playerGateways().rejectedHeartbeats());
        assertEquals(15, snapshot.playerGateways().rateLimitedHeartbeats());
        assertEquals(15, snapshot.playerGateways().acceptedAcks());
        assertEquals(15, snapshot.playerGateways().rejectedAcks());
        assertEquals(15, snapshot.playerGateways().slowClientClosures());
        assertEquals(16, snapshot.playerGateways().invalidFrames());
        assertEquals(17, snapshot.playerGateways().disconnectedSessions());
        assertEquals(18, snapshot.playerGateways().idleTimeouts());
        assertEquals(5, snapshot.playerGateways().rejectedCommandsByCode()
                .get(PlayerClientErrorCode.NOT_LOGGED_IN));
        assertEquals(5, snapshot.playerGateways().rejectedCommandsByCode()
                .get(PlayerClientErrorCode.SESSION_EXPIRED));
        assertEquals(7, snapshot.playerGateways().rejectedCommandsByCode()
                .get(PlayerClientErrorCode.COMMAND_RATE_LIMITED));
        assertTrue(json.contains("\"playerGateways\":{\"gatewayCount\":2,\"acceptedLogins\":15"));
        assertTrue(json.contains("\"rejectedCommandsByCode\""));
        assertTrue(metrics.contains("commonbattle_player_gateway_accepted_logins_total 15"));
        assertTrue(metrics.contains(
                "commonbattle_player_gateway_rejected_commands_by_code_total{code=\"NOT_LOGGED_IN\"} 5"));
        assertTrue(metrics.contains("commonbattle_player_gateway_slow_client_closures_total 15"));
        assertTrue(metrics.contains("commonbattle_player_gateway_idle_timeouts_total 18"));
    }
}
