package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsHttpServerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void liveReadyAndHealthReturnJsonWhenRuntimeIsUp() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors)
        )) {
            server.start();

            HttpResult live = get(server, "/live");
            HttpResult ready = get(server, "/ready");
            HttpResult health = get(server, "/health");
            HttpResult metrics = get(server, "/metrics");

            assertEquals(200, live.statusCode());
            assertEquals("{\"status\":\"UP\"}", live.body());
            assertEquals(200, ready.statusCode());
            assertEquals("{\"status\":\"UP\",\"draining\":false}", ready.body());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"UP\""));
            assertTrue(health.body().contains("\"actorSystem\""));
            assertTrue(health.body().contains("\"registryLeases\""));
            assertTrue(health.body().contains("\"networkTransports\""));
            assertTrue(health.body().contains("\"eventCenters\""));
            assertTrue(health.body().contains("\"eventSubscriptions\""));
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.contentType().startsWith("text/plain"));
            assertTrue(metrics.body().contains("commonbattle_runtime_status{status=\"UP\"} 1"));
            assertTrue(metrics.body().contains("commonbattle_actor_queued_tasks 0"));
            assertTrue(metrics.body().contains("commonbattle_rpc_pending_requests 0"));
            assertTrue(metrics.body().contains("commonbattle_network_connection_failures_total 0"));
            assertTrue(metrics.body().contains("commonbattle_event_center_dropped_events_total 0"));
            assertTrue(metrics.body().contains("commonbattle_event_subscription_failures_total 0"));
        }
    }

    @Test
    void readyAndHealthReturnUnavailableWhenRuntimeIsDown() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        actors.close();
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors)
        )) {
            server.start();

            HttpResult live = get(server, "/live");
            HttpResult ready = get(server, "/ready");
            HttpResult health = get(server, "/health");
            HttpResult metrics = get(server, "/metrics");

            assertEquals(503, live.statusCode());
            assertEquals("{\"status\":\"DOWN\"}", live.body());
            assertEquals(503, ready.statusCode());
            assertEquals("{\"status\":\"DOWN\",\"draining\":false}", ready.body());
            assertEquals(503, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"DOWN\""));
            assertEquals(503, metrics.statusCode());
            assertTrue(metrics.body().contains("commonbattle_runtime_status{status=\"DOWN\"} 1"));
        }
    }

    @Test
    void postDrainMarksReadyUnavailableAndReturnsDrainResult() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        RuntimeHealthProbe probe = probe(actors);
        ServerDrainController controller = new ServerDrainController(
                probe,
                CLOCK,
                duration -> {
                }
        );
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe,
                controller,
                new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(1))
        )) {
            server.start();

            HttpResult drain = post(server, "/drain");
            HttpResult ready = get(server, "/ready");

            assertEquals(200, drain.statusCode());
            assertEquals("{\"drained\":true,\"elapsedMillis\":0,\"status\":\"UP\"}", drain.body());
            assertEquals(503, ready.statusCode());
            assertEquals("{\"status\":\"UP\",\"draining\":true}", ready.body());
        }
    }

    @Test
    void drainRequiresPostAndConfiguredController() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors)
        )) {
            server.start();

            HttpResult getDrain = get(server, "/drain");
            HttpResult postDrain = post(server, "/drain");

            assertEquals(405, getDrain.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", getDrain.body());
            assertEquals(503, postDrain.statusCode());
            assertEquals("{\"drained\":false,\"reason\":\"drain_not_configured\"}", postDrain.body());
        }
    }

    private static RuntimeHealthProbe probe(ActorSystem actors) {
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        return new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(local, actors, new InMemoryAgentDirectory(), CLOCK),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                RuntimeHealthPolicy.defaults()
        );
    }

    private static HttpResult get(OpsHttpServer server, String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return new HttpResult(response.statusCode(), contentType(response), response.body());
    }

    private static HttpResult post(OpsHttpServer server, String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return new HttpResult(response.statusCode(), contentType(response), response.body());
    }

    private static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    private record HttpResult(int statusCode, String contentType, String body) {
    }
}
