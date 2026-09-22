package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorFailure;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSlowTask;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.DeadLetter;
import com.commonbattle.actor.DeadLetterReason;
import com.commonbattle.actor.agent.migration.ActorHotspotMigrationAdmin;
import com.commonbattle.actor.agent.migration.ActorHotspotMigrationResult;
import com.commonbattle.actor.agent.migration.ActorHotspotMigrationStatus;
import com.commonbattle.actor.backpressure.ActorHotspotAdmissionController;
import com.commonbattle.actor.backpressure.ActorHotspotOverrideMode;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.event.OwnerEventRepairIsolatedOwner;
import com.commonbattle.game.event.OwnerEventRepairIsolationAdmin;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertEquals("{\"drained\":true,\"elapsedMillis\":0,\"status\":\"UP\",\"reason\":\"\"}", drain.body());
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

    @Test
    void ownerRepairIsolationCanBeListedAndReleased() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        RecordingRepairIsolationAdmin admin = new RecordingRepairIsolationAdmin();
        admin.owners.add(new OwnerEventRepairIsolatedOwner("friend:10001", 8, 5000));
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors),
                null,
                DrainConfig.defaults(),
                List.of(admin)
        )) {
            server.start();

            HttpResult list = get(server, "/owner-repair/isolated");
            HttpResult release = post(server, "/owner-repair/release?ownerKey=friend%3A10001");
            HttpResult listAfterRelease = get(server, "/owner-repair/isolated");

            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains("\"ownerKey\":\"friend:10001\""));
            assertEquals(200, release.statusCode());
            assertEquals("{\"released\":1,\"ownerKey\":\"friend:10001\"}", release.body());
            assertEquals("{\"owners\":[]}", listAfterRelease.body());
        }
    }

    @Test
    void ownerRepairReleaseValidatesMethodAndOwnerKey() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors),
                null,
                DrainConfig.defaults(),
                List.of(new RecordingRepairIsolationAdmin())
        )) {
            server.start();

            HttpResult getRelease = get(server, "/owner-repair/release?ownerKey=missing");
            HttpResult missingOwner = post(server, "/owner-repair/release");
            HttpResult notFound = post(server, "/owner-repair/release?ownerKey=missing");

            assertEquals(405, getRelease.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", getRelease.body());
            assertEquals(400, missingOwner.statusCode());
            assertEquals("{\"released\":0,\"error\":\"missing_owner_key\"}", missingOwner.body());
            assertEquals(404, notFound.statusCode());
            assertEquals("{\"released\":0,\"ownerKey\":\"missing\"}", notFound.body());
        }
    }

    @Test
    void ownerRepairIsolationCanReleaseAllOwners() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        RecordingRepairIsolationAdmin first = new RecordingRepairIsolationAdmin();
        first.owners.add(new OwnerEventRepairIsolatedOwner("friend:10001", 8, 5000));
        RecordingRepairIsolationAdmin second = new RecordingRepairIsolationAdmin();
        second.owners.add(new OwnerEventRepairIsolatedOwner("alliance:7", 5, 3000));
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors),
                null,
                DrainConfig.defaults(),
                List.of(first, second)
        )) {
            server.start();

            HttpResult getReleaseAll = get(server, "/owner-repair/release-all");
            HttpResult releaseAll = post(server, "/owner-repair/release-all");
            HttpResult listAfterRelease = get(server, "/owner-repair/isolated");

            assertEquals(405, getReleaseAll.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", getReleaseAll.body());
            assertEquals(200, releaseAll.statusCode());
            assertEquals("{\"released\":2}", releaseAll.body());
            assertEquals("{\"owners\":[]}", listAfterRelease.body());
        }
    }

    @Test
    void ownerRepairReleaseOpsAreAudited() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        RecordingRepairIsolationAdmin admin = new RecordingRepairIsolationAdmin();
        admin.owners.add(new OwnerEventRepairIsolatedOwner("friend:10001", 8, 5000));
        InMemoryOwnerRepairOpsAuditLog auditLog = new InMemoryOwnerRepairOpsAuditLog();
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors, auditLog),
                null,
                DrainConfig.defaults(),
                List.of(admin),
                auditLog
        )) {
            server.start();

            HttpResult getAuditByWrongMethod = post(server, "/owner-repair/audit");
            HttpResult release = post(server, "/owner-repair/release?ownerKey=friend%3A10001");
            HttpResult audit = get(server, "/owner-repair/audit");
            HttpResult health = get(server, "/health");
            HttpResult metrics = get(server, "/metrics");

            assertEquals(405, getAuditByWrongMethod.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", getAuditByWrongMethod.body());
            assertEquals(200, release.statusCode());
            assertEquals(200, audit.statusCode());
            assertTrue(audit.body().contains("\"action\":\"release\""));
            assertTrue(audit.body().contains("\"ownerKey\":\"friend:10001\""));
            assertTrue(audit.body().contains("\"released\":1"));
            assertTrue(audit.body().contains("\"remote\":\"/127.0.0.1:"));
            assertTrue(audit.body().contains("\"at\":\""));
            assertTrue(health.body().contains("\"ownerRepairOps\":{\"auditCount\":1,\"retainedEntries\":1,"
                    + "\"recordedEntries\":1,"
                    + "\"releaseOps\":1,\"releaseAllOps\":0,\"releasedOwners\":1,\"notFoundReleaseOps\":0,"
                    + "\"droppedEntries\":0}"));
            assertTrue(metrics.body().contains("commonbattle_owner_repair_ops_audit_recorded_total 1"));
            assertTrue(metrics.body().contains("commonbattle_owner_repair_ops_release_total 1"));
            assertTrue(metrics.body().contains("commonbattle_owner_repair_ops_released_owners_total 1"));
        }
    }

    @Test
    void ownerRepairReleaseAllOpsAreAudited() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        RecordingRepairIsolationAdmin first = new RecordingRepairIsolationAdmin();
        first.owners.add(new OwnerEventRepairIsolatedOwner("friend:10001", 8, 5000));
        RecordingRepairIsolationAdmin second = new RecordingRepairIsolationAdmin();
        second.owners.add(new OwnerEventRepairIsolatedOwner("alliance:7", 5, 3000));
        InMemoryOwnerRepairOpsAuditLog auditLog = new InMemoryOwnerRepairOpsAuditLog();
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors, auditLog),
                null,
                DrainConfig.defaults(),
                List.of(first, second),
                auditLog
        )) {
            server.start();

            HttpResult releaseAll = post(server, "/owner-repair/release-all");
            HttpResult audit = get(server, "/owner-repair/audit");
            HttpResult health = get(server, "/health");
            HttpResult metrics = get(server, "/metrics");

            assertEquals(200, releaseAll.statusCode());
            assertEquals(200, audit.statusCode());
            assertTrue(audit.body().contains("\"action\":\"release-all\""));
            assertTrue(audit.body().contains("\"ownerKey\":\"*\""));
            assertTrue(audit.body().contains("\"released\":2"));
            assertTrue(health.body().contains("\"ownerRepairOps\":{\"auditCount\":1,\"retainedEntries\":1,"
                    + "\"recordedEntries\":1,"
                    + "\"releaseOps\":0,\"releaseAllOps\":1,\"releasedOwners\":2,\"notFoundReleaseOps\":0,"
                    + "\"droppedEntries\":0}"));
            assertTrue(metrics.body().contains("commonbattle_owner_repair_ops_release_all_total 1"));
            assertTrue(metrics.body().contains("commonbattle_owner_repair_ops_released_owners_total 2"));
        }
    }

    @Test
    void ownerRepairAuditCanBeFilteredAndPaged() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        RecordingRepairIsolationAdmin admin = new RecordingRepairIsolationAdmin();
        admin.owners.add(new OwnerEventRepairIsolatedOwner("friend:10001", 8, 5000));
        admin.owners.add(new OwnerEventRepairIsolatedOwner("friend:10002", 8, 5000));
        admin.owners.add(new OwnerEventRepairIsolatedOwner("alliance:7", 5, 3000));
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors),
                null,
                DrainConfig.defaults(),
                List.of(admin)
        )) {
            server.start();

            post(server, "/owner-repair/release?ownerKey=friend%3A10001");
            post(server, "/owner-repair/release?ownerKey=missing");
            post(server, "/owner-repair/release-all");

            HttpResult releaseAllOnly = get(server, "/owner-repair/audit?action=release-all");
            HttpResult missingOnly = get(server, "/owner-repair/audit?ownerKey=missing");
            HttpResult paged = get(server, "/owner-repair/audit?offset=1&limit=1");
            HttpResult invalidLimit = get(server, "/owner-repair/audit?limit=0");
            HttpResult invalidAction = get(server, "/owner-repair/audit?action=unknown");

            assertEquals(200, releaseAllOnly.statusCode());
            assertTrue(releaseAllOnly.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(releaseAllOnly.body().contains("\"action\":\"release-all\""));
            assertFalse(releaseAllOnly.body().contains("\"ownerKey\":\"missing\""));

            assertEquals(200, missingOnly.statusCode());
            assertTrue(missingOnly.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(missingOnly.body().contains("\"ownerKey\":\"missing\""));
            assertTrue(missingOnly.body().contains("\"released\":0"));

            assertEquals(200, paged.statusCode());
            assertTrue(paged.body().contains("\"matched\":3,\"offset\":1,\"limit\":1"));
            assertTrue(paged.body().contains("\"ownerKey\":\"missing\""));
            assertFalse(paged.body().contains("\"ownerKey\":\"friend:10001\""));
            assertFalse(paged.body().contains("\"action\":\"release-all\""));

            assertEquals(400, invalidLimit.statusCode());
            assertEquals("{\"error\":\"invalid_limit\"}", invalidLimit.body());
            assertEquals(400, invalidAction.statusCode());
            assertEquals("{\"error\":\"invalid_action\"}", invalidAction.body());
        }
    }

    @Test
    void ownerRepairAuditHonorsConfiguredCapacityAndNotFoundRetention() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        RecordingRepairIsolationAdmin admin = new RecordingRepairIsolationAdmin();
        admin.owners.add(new OwnerEventRepairIsolatedOwner("friend:10001", 8, 5000));
        admin.owners.add(new OwnerEventRepairIsolatedOwner("friend:10002", 8, 5000));
        InMemoryOwnerRepairOpsAuditLog auditLog = new InMemoryOwnerRepairOpsAuditLog(
                new OwnerRepairOpsAuditConfig(1, false)
        );
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors, auditLog),
                null,
                DrainConfig.defaults(),
                List.of(admin),
                auditLog
        )) {
            server.start();

            post(server, "/owner-repair/release?ownerKey=missing");
            post(server, "/owner-repair/release?ownerKey=friend%3A10001");
            post(server, "/owner-repair/release?ownerKey=friend%3A10002");
            HttpResult audit = get(server, "/owner-repair/audit");
            HttpResult health = get(server, "/health");

            assertEquals(200, audit.statusCode());
            assertTrue(audit.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(audit.body().contains("\"ownerKey\":\"friend:10002\""));
            assertFalse(audit.body().contains("\"ownerKey\":\"missing\""));
            assertFalse(audit.body().contains("\"ownerKey\":\"friend:10001\""));
            assertTrue(health.body().contains("\"ownerRepairOps\":{\"auditCount\":1,\"retainedEntries\":1,"
                    + "\"recordedEntries\":3,\"releaseOps\":3,\"releaseAllOps\":0,\"releasedOwners\":2,"
                    + "\"notFoundReleaseOps\":1,\"droppedEntries\":1}"));
        }
    }

    @Test
    void adminTokenProtectsDrainAndOwnerRepairOps() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        RecordingRepairIsolationAdmin admin = new RecordingRepairIsolationAdmin();
        admin.owners.add(new OwnerEventRepairIsolatedOwner("friend:10001", 8, 5000));
        InMemoryOwnerRepairOpsAuditLog auditLog = new InMemoryOwnerRepairOpsAuditLog();
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors, auditLog),
                null,
                DrainConfig.defaults(),
                List.of(admin),
                auditLog,
                new OpsHttpSecurityConfig("secret", "X-Ops-Token", "X-Ops-Operator")
        )) {
            server.start();

            HttpResult health = get(server, "/health");
            HttpResult unauthDrain = post(server, "/drain");
            HttpResult unauthList = get(server, "/owner-repair/isolated");
            HttpResult wrongToken = post(server, "/owner-repair/release?ownerKey=friend%3A10001",
                    "bad", "gm-1");
            HttpResult release = post(server, "/owner-repair/release?ownerKey=friend%3A10001",
                    "secret", "gm-1");
            HttpResult audit = get(server, "/owner-repair/audit", "secret", null);

            assertEquals(200, health.statusCode());
            assertEquals(401, unauthDrain.statusCode());
            assertEquals("{\"error\":\"unauthorized\"}", unauthDrain.body());
            assertEquals(401, unauthList.statusCode());
            assertEquals(401, wrongToken.statusCode());
            assertEquals(200, release.statusCode());
            assertEquals("{\"released\":1,\"ownerKey\":\"friend:10001\"}", release.body());
            assertEquals(200, audit.statusCode());
            assertTrue(audit.body().contains("\"operator\":\"gm-1\""));
            assertTrue(audit.body().contains("\"ownerKey\":\"friend:10001\""));
        }
    }

    @Test
    void actorIncidentsCanBeListedFilteredAndPaged() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        InMemoryActorIncidentLog incidents = new InMemoryActorIncidentLog(8, CLOCK);
        ActorRef player = new ActorRef("player-10001");
        ActorRef scene = new ActorRef("scene-shard:world-1:0");
        incidents.accept(new DeadLetter(
                player,
                ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND, ignored -> {
                }),
                DeadLetterReason.MAILBOX_FULL
        ));
        incidents.onFailure(new ActorFailure(
                player,
                ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK, ignored -> {
                }),
                new IllegalStateException("boom")
        ));
        incidents.accept(new DeadLetter(
                scene,
                ActorTask.categorized(ActorTaskCategory.TIMER, ignored -> {
                }),
                DeadLetterReason.SYSTEM_CLOSED
        ));
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors, null, incidents),
                null,
                DrainConfig.defaults(),
                List.of(),
                new InMemoryOwnerRepairOpsAuditLog(),
                new InMemoryOwnerRepairOpsAuditLog(),
                OpsHttpSecurityConfig.disabled(),
                List.of(incidents)
        )) {
            server.start();

            HttpResult all = get(server, "/actor-incidents");
            HttpResult poison = get(server, "/actor-incidents?kind=POISON_MESSAGE");
            HttpResult playerOnly = get(server, "/actor-incidents?actorId=player-10001");
            HttpResult timerOnly = get(server, "/actor-incidents?category=TIMER");
            HttpResult mailboxFull = get(server, "/actor-incidents?reason=MAILBOX_FULL");
            HttpResult paged = get(server, "/actor-incidents?offset=1&limit=1");
            HttpResult invalidKind = get(server, "/actor-incidents?kind=BAD");
            HttpResult invalidCategory = get(server, "/actor-incidents?category=BAD");
            HttpResult invalidLimit = get(server, "/actor-incidents?limit=0");
            HttpResult wrongMethod = post(server, "/actor-incidents");

            assertEquals(200, all.statusCode());
            assertTrue(all.body().contains("\"matched\":3,\"offset\":0,\"limit\":128"));
            assertTrue(all.body().contains("\"kind\":\"DEAD_LETTER\""));
            assertTrue(all.body().contains("\"kind\":\"POISON_MESSAGE\""));
            assertTrue(all.body().contains("\"actorId\":\"scene-shard:world-1:0\""));

            assertEquals(200, poison.statusCode());
            assertTrue(poison.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(poison.body().contains("\"errorType\":\"java.lang.IllegalStateException\""));
            assertFalse(poison.body().contains("\"reason\":\"MAILBOX_FULL\""));

            assertEquals(200, playerOnly.statusCode());
            assertTrue(playerOnly.body().contains("\"matched\":2,\"offset\":0,\"limit\":128"));
            assertFalse(playerOnly.body().contains("\"actorId\":\"scene-shard:world-1:0\""));

            assertEquals(200, timerOnly.statusCode());
            assertTrue(timerOnly.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(timerOnly.body().contains("\"category\":\"TIMER\""));

            assertEquals(200, mailboxFull.statusCode());
            assertTrue(mailboxFull.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(mailboxFull.body().contains("\"reason\":\"MAILBOX_FULL\""));

            assertEquals(200, paged.statusCode());
            assertTrue(paged.body().contains("\"matched\":3,\"offset\":1,\"limit\":1"));
            assertTrue(paged.body().contains("\"kind\":\"POISON_MESSAGE\""));
            assertFalse(paged.body().contains("\"reason\":\"SYSTEM_CLOSED\""));

            assertEquals(400, invalidKind.statusCode());
            assertEquals("{\"error\":\"invalid_kind\"}", invalidKind.body());
            assertEquals(400, invalidCategory.statusCode());
            assertEquals("{\"error\":\"invalid_category\"}", invalidCategory.body());
            assertEquals(400, invalidLimit.statusCode());
            assertEquals("{\"error\":\"invalid_limit\"}", invalidLimit.body());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", wrongMethod.body());
        }
    }

    @Test
    void actorMailboxesCanBeListedFilteredAndPaged() throws Exception {
        ActorSystem actors = new ActorSystem(ignored -> {
        }, 64);
        actors.send(new ActorRef("player-10001"),
                ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND, ignored -> {
                }));
        actors.send(new ActorRef("player-10001"),
                ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK, ignored -> {
                }));
        actors.send(new ActorRef("scene-shard:world-1:0"),
                ActorTask.categorized(ActorTaskCategory.TIMER, ignored -> {
                }));
        actors.send(new ActorRef("chat-world"),
                ActorTask.categorized(ActorTaskCategory.EVENT, ignored -> {
                }));
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors)
        )) {
            server.start();

            HttpResult all = get(server, "/actor-mailboxes");
            HttpResult playerGroup = get(server, "/actor-mailboxes?group=player");
            HttpResult scenePrefix = get(server, "/actor-mailboxes?actorIdPrefix=scene-shard");
            HttpResult rpcCallback = get(server, "/actor-mailboxes?category=RPC_CALLBACK");
            HttpResult hotOnly = get(server, "/actor-mailboxes?minQueuedTasks=2");
            HttpResult paged = get(server, "/actor-mailboxes?offset=1&limit=1");
            HttpResult invalidCategory = get(server, "/actor-mailboxes?category=BAD");
            HttpResult invalidMinQueuedTasks = get(server, "/actor-mailboxes?minQueuedTasks=-1");
            HttpResult invalidLimit = get(server, "/actor-mailboxes?limit=0");
            HttpResult wrongMethod = post(server, "/actor-mailboxes");

            assertEquals(200, all.statusCode());
            assertTrue(all.body().contains("\"matched\":3,\"offset\":0,\"limit\":128"));
            assertTrue(all.body().contains("\"actorId\":\"player-10001\""));
            assertTrue(all.body().contains("\"group\":\"scene-shard\""));
            assertTrue(all.body().contains("\"queuedTasksByCategory\""));

            assertEquals(200, playerGroup.statusCode());
            assertTrue(playerGroup.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(playerGroup.body().contains("\"actorId\":\"player-10001\""));
            assertFalse(playerGroup.body().contains("\"actorId\":\"chat-world\""));

            assertEquals(200, scenePrefix.statusCode());
            assertTrue(scenePrefix.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(scenePrefix.body().contains("\"actorId\":\"scene-shard:world-1:0\""));

            assertEquals(200, rpcCallback.statusCode());
            assertTrue(rpcCallback.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(rpcCallback.body().contains("\"RPC_CALLBACK\":1"));

            assertEquals(200, hotOnly.statusCode());
            assertTrue(hotOnly.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(hotOnly.body().contains("\"queuedTasks\":2"));
            assertFalse(hotOnly.body().contains("\"actorId\":\"scene-shard:world-1:0\""));

            assertEquals(200, paged.statusCode());
            assertTrue(paged.body().contains("\"matched\":3,\"offset\":1,\"limit\":1"));
            assertTrue(paged.body().contains("\"actorId\":\"chat-world\""));
            assertFalse(paged.body().contains("\"actorId\":\"player-10001\""));

            assertEquals(400, invalidCategory.statusCode());
            assertEquals("{\"error\":\"invalid_category\"}", invalidCategory.body());
            assertEquals(400, invalidMinQueuedTasks.statusCode());
            assertEquals("{\"error\":\"invalid_minQueuedTasks\"}", invalidMinQueuedTasks.body());
            assertEquals(400, invalidLimit.statusCode());
            assertEquals("{\"error\":\"invalid_limit\"}", invalidLimit.body());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", wrongMethod.body());
        }
    }

    @Test
    void actorSlowTasksCanBeListedFilteredAndPaged() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        InMemoryActorSlowTaskLog slowTasks = new InMemoryActorSlowTaskLog(8, CLOCK);
        slowTasks.accept(new ActorSlowTask(new ActorRef("player-10001"), ActorTaskCategory.PLAYER_COMMAND,
                Duration.ofMillis(25), Duration.ofMillis(10)));
        slowTasks.accept(new ActorSlowTask(new ActorRef("player-10001"), ActorTaskCategory.RPC_CALLBACK,
                Duration.ofMillis(40), Duration.ofMillis(10)));
        slowTasks.accept(new ActorSlowTask(new ActorRef("scene-shard:world-1:0"), ActorTaskCategory.TIMER,
                Duration.ofMillis(15), Duration.ofMillis(10)));
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors, null, null, slowTasks),
                null,
                DrainConfig.defaults(),
                List.of(),
                new InMemoryOwnerRepairOpsAuditLog(),
                new InMemoryOwnerRepairOpsAuditLog(),
                OpsHttpSecurityConfig.disabled(),
                List.of(),
                List.of(slowTasks)
        )) {
            server.start();

            HttpResult all = get(server, "/actor-slow-tasks");
            HttpResult playerOnly = get(server, "/actor-slow-tasks?actorId=player-10001");
            HttpResult callbackOnly = get(server, "/actor-slow-tasks?category=RPC_CALLBACK");
            HttpResult minElapsed = get(server, "/actor-slow-tasks?minElapsedMillis=30");
            HttpResult paged = get(server, "/actor-slow-tasks?offset=1&limit=1");
            HttpResult health = get(server, "/health");
            HttpResult metrics = get(server, "/metrics");
            HttpResult invalidCategory = get(server, "/actor-slow-tasks?category=BAD");
            HttpResult invalidMinElapsed = get(server, "/actor-slow-tasks?minElapsedMillis=-1");
            HttpResult invalidLimit = get(server, "/actor-slow-tasks?limit=0");
            HttpResult wrongMethod = post(server, "/actor-slow-tasks");

            assertEquals(200, all.statusCode());
            assertTrue(all.body().contains("\"matched\":3,\"offset\":0,\"limit\":128"));
            assertTrue(all.body().contains("\"actorId\":\"player-10001\""));
            assertTrue(all.body().contains("\"elapsedMillis\":40"));
            assertTrue(all.body().contains("\"thresholdMillis\":10"));

            assertEquals(200, playerOnly.statusCode());
            assertTrue(playerOnly.body().contains("\"matched\":2,\"offset\":0,\"limit\":128"));
            assertFalse(playerOnly.body().contains("\"actorId\":\"scene-shard:world-1:0\""));

            assertEquals(200, callbackOnly.statusCode());
            assertTrue(callbackOnly.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(callbackOnly.body().contains("\"category\":\"RPC_CALLBACK\""));

            assertEquals(200, minElapsed.statusCode());
            assertTrue(minElapsed.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(minElapsed.body().contains("\"elapsedMillis\":40"));

            assertEquals(200, paged.statusCode());
            assertTrue(paged.body().contains("\"matched\":3,\"offset\":1,\"limit\":1"));
            assertTrue(paged.body().contains("\"category\":\"RPC_CALLBACK\""));
            assertFalse(paged.body().contains("\"category\":\"TIMER\""));

            assertTrue(health.body().contains("\"actorSlowTasks\""));
            assertTrue(health.body().contains("\"maxElapsedMillis\":40"));
            assertTrue(metrics.body().contains("commonbattle_actor_slow_task_recorded_total 3"));

            assertEquals(400, invalidCategory.statusCode());
            assertEquals("{\"error\":\"invalid_category\"}", invalidCategory.body());
            assertEquals(400, invalidMinElapsed.statusCode());
            assertEquals("{\"error\":\"invalid_minElapsedMillis\"}", invalidMinElapsed.body());
            assertEquals(400, invalidLimit.statusCode());
            assertEquals("{\"error\":\"invalid_limit\"}", invalidLimit.body());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", wrongMethod.body());
        }
    }

    @Test
    void actorHotspotsCombineMailboxAndSlowTaskSignals() throws Exception {
        ActorSystem actors = new ActorSystem(ignored -> {
        }, 64);
        actors.send(new ActorRef("player-10001"), ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND,
                ignored -> {
                }));
        actors.send(new ActorRef("player-10002"), ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND,
                ignored -> {
                }));
        actors.send(new ActorRef("player-10002"), ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK,
                ignored -> {
                }));
        actors.send(new ActorRef("scene-shard:world-1:0"), ActorTask.categorized(ActorTaskCategory.TIMER,
                ignored -> {
                }));
        actors.send(new ActorRef("scene-shard:world-1:0"), ActorTask.categorized(ActorTaskCategory.TIMER,
                ignored -> {
                }));
        actors.send(new ActorRef("scene-shard:world-1:0"), ActorTask.categorized(ActorTaskCategory.TIMER,
                ignored -> {
                }));
        InMemoryActorSlowTaskLog slowTasks = new InMemoryActorSlowTaskLog(8, CLOCK);
        slowTasks.accept(new ActorSlowTask(new ActorRef("chat-world"), ActorTaskCategory.EVENT,
                Duration.ofMillis(150), Duration.ofMillis(10)));
        ActorHotspotPolicy policy = new ActorHotspotPolicy(1, 2, 3, 2, 3, 100, 500);
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors, null, null, slowTasks),
                null,
                DrainConfig.defaults(),
                List.of(),
                new InMemoryOwnerRepairOpsAuditLog(),
                new InMemoryOwnerRepairOpsAuditLog(),
                OpsHttpSecurityConfig.disabled(),
                List.of(),
                List.of(slowTasks),
                policy
        )) {
            server.start();

            HttpResult all = get(server, "/actor-hotspots");
            HttpResult migration = get(server, "/actor-hotspots?action=MIGRATION_CANDIDATE");
            HttpResult playerGroup = get(server, "/actor-hotspots?group=player");
            HttpResult prefix = get(server, "/actor-hotspots?actorIdPrefix=chat");
            HttpResult paged = get(server, "/actor-hotspots?offset=1&limit=1");
            HttpResult invalidAction = get(server, "/actor-hotspots?action=BAD");
            HttpResult invalidLimit = get(server, "/actor-hotspots?limit=0");
            HttpResult wrongMethod = post(server, "/actor-hotspots");

            assertEquals(200, all.statusCode());
            assertTrue(all.body().contains("\"matched\":4,\"offset\":0,\"limit\":128"));
            assertTrue(all.body().contains("\"actorId\":\"scene-shard:world-1:0\""));
            assertTrue(all.body().contains("\"action\":\"MIGRATION_CANDIDATE\""));
            assertTrue(all.body().contains("\"actorId\":\"chat-world\""));
            assertTrue(all.body().contains("\"maxSlowTaskMillis\":150"));

            assertEquals(200, migration.statusCode());
            assertTrue(migration.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(migration.body().contains("\"actorId\":\"scene-shard:world-1:0\""));

            assertEquals(200, playerGroup.statusCode());
            assertTrue(playerGroup.body().contains("\"matched\":2,\"offset\":0,\"limit\":128"));
            assertFalse(playerGroup.body().contains("\"actorId\":\"chat-world\""));

            assertEquals(200, prefix.statusCode());
            assertTrue(prefix.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertTrue(prefix.body().contains("\"actorId\":\"chat-world\""));

            assertEquals(200, paged.statusCode());
            assertTrue(paged.body().contains("\"matched\":4,\"offset\":1,\"limit\":1"));
            assertTrue(paged.body().contains("\"action\":\"THROTTLE\""));

            assertEquals(400, invalidAction.statusCode());
            assertEquals("{\"error\":\"invalid_action\"}", invalidAction.body());
            assertEquals(400, invalidLimit.statusCode());
            assertEquals("{\"error\":\"invalid_limit\"}", invalidLimit.body());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", wrongMethod.body());
        }
    }

    @Test
    void actorHotspotOverridesCanBeSetListedAndCleared() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        ActorHotspotAdmissionController controller = new ActorHotspotAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                List.of(),
                ActorHotspotPolicy.defaults(),
                Duration.ofMillis(100)
        );
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors),
                null,
                DrainConfig.defaults(),
                List.of(),
                new InMemoryOwnerRepairOpsAuditLog(),
                new InMemoryOwnerRepairOpsAuditLog(),
                OpsHttpSecurityConfig.disabled(),
                List.of(),
                List.of(),
                ActorHotspotPolicy.defaults(),
                List.of(controller)
        )) {
            server.start();

            HttpResult set = post(server, "/actor-hotspot-overrides/set?actorId=player-10001"
                    + "&mode=" + ActorHotspotOverrideMode.THROTTLE.name()
                    + "&ttlMillis=60000&reason=manual");
            HttpResult all = get(server, "/actor-hotspot-overrides");
            HttpResult filtered = get(server, "/actor-hotspot-overrides?mode=THROTTLE&group=player");
            HttpResult invalidMode = post(server, "/actor-hotspot-overrides/set?actorId=player-10001&mode=BAD");
            HttpResult invalidLimit = get(server, "/actor-hotspot-overrides?limit=0");
            HttpResult wrongMethod = get(server, "/actor-hotspot-overrides/set?actorId=player-10001&mode=EXEMPT");
            HttpResult clear = post(server, "/actor-hotspot-overrides/clear?actorId=player-10001");
            HttpResult listAfterClear = get(server, "/actor-hotspot-overrides");

            assertEquals(200, set.statusCode());
            assertTrue(set.body().contains("\"updated\":1"));
            assertTrue(set.body().contains("\"actorId\":\"player-10001\""));
            assertTrue(set.body().contains("\"mode\":\"THROTTLE\""));
            assertTrue(set.body().contains("\"reason\":\"manual\""));

            assertEquals(200, all.statusCode());
            assertTrue(all.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertEquals(200, filtered.statusCode());
            assertTrue(filtered.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertEquals(400, invalidMode.statusCode());
            assertEquals("{\"updated\":0,\"error\":\"invalid_mode\"}", invalidMode.body());
            assertEquals(400, invalidLimit.statusCode());
            assertEquals("{\"error\":\"invalid_limit\"}", invalidLimit.body());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", wrongMethod.body());

            assertEquals(200, clear.statusCode());
            assertEquals("{\"cleared\":1,\"actorId\":\"player-10001\"}", clear.body());
            assertEquals(200, listAfterClear.statusCode());
            assertTrue(listAfterClear.body().contains("\"matched\":0,\"offset\":0,\"limit\":128"));
        }
    }

    @Test
    void actorHotspotMigrationsCanSubmitCurrentMigrationCandidates() throws Exception {
        ActorSystem actors = new ActorSystem(ignored -> {
        }, 64);
        actors.send(new ActorRef("player-10001"), ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND,
                ignored -> {
                }));
        actors.send(new ActorRef("player-10001"), ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND,
                ignored -> {
                }));
        actors.send(new ActorRef("player-10001"), ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK,
                ignored -> {
                }));
        actors.send(new ActorRef("player-10002"), ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND,
                ignored -> {
                }));
        actors.send(new ActorRef("player-10002"), ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK,
                ignored -> {
                }));
        ActorHotspotPolicy policy = new ActorHotspotPolicy(1, 2, 3, 2, 3, 100, 500);
        RecordingHotspotMigrationAdmin migrations = new RecordingHotspotMigrationAdmin();
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors),
                null,
                DrainConfig.defaults(),
                List.of(),
                new InMemoryOwnerRepairOpsAuditLog(),
                new InMemoryOwnerRepairOpsAuditLog(),
                OpsHttpSecurityConfig.disabled(),
                List.of(),
                List.of(),
                policy,
                List.of(),
                List.of(migrations)
        )) {
            server.start();

            HttpResult submit = post(server, "/actor-hotspot-migrations/submit?maxSubmissions=8");
            HttpResult invalidMax = post(server, "/actor-hotspot-migrations/submit?maxSubmissions=0");
            HttpResult wrongMethod = get(server, "/actor-hotspot-migrations/submit");

            assertEquals(200, submit.statusCode());
            assertTrue(submit.body().contains("\"submitted\":1"));
            assertTrue(submit.body().contains("\"candidates\":1"));
            assertTrue(submit.body().contains("\"actorId\":\"player-10001\""));
            assertTrue(submit.body().contains("\"status\":\"SUBMITTED\""));
            assertEquals(1, migrations.candidates.size());
            assertEquals("player-10001", migrations.candidates.getFirst().actorId());

            assertEquals(400, invalidMax.statusCode());
            assertEquals("{\"submitted\":0,\"error\":\"invalid_maxSubmissions\"}", invalidMax.body());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("{\"error\":\"method_not_allowed\"}", wrongMethod.body());
        }
    }

    @Test
    void actorHotspotMigrationSubmitRequiresConfiguredAdmin() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors)
        )) {
            server.start();

            HttpResult submit = post(server, "/actor-hotspot-migrations/submit");

            assertEquals(503, submit.statusCode());
            assertEquals("{\"submitted\":0,\"error\":\"hotspot_migration_not_configured\"}", submit.body());
        }
    }

    @Test
    void adminTokenProtectsActorIncidentQuery() throws Exception {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        InMemoryActorIncidentLog incidents = new InMemoryActorIncidentLog(8, CLOCK);
        InMemoryActorSlowTaskLog slowTasks = new InMemoryActorSlowTaskLog(8, CLOCK);
        slowTasks.accept(new ActorSlowTask(new ActorRef("player-10001"), ActorTaskCategory.PLAYER_COMMAND,
                Duration.ofMillis(20), Duration.ofMillis(10)));
        incidents.accept(new DeadLetter(new ActorRef("player-10001"), ignored -> {
        }, DeadLetterReason.MAILBOX_FULL));
        try (OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                probe(actors, null, incidents, slowTasks),
                null,
                DrainConfig.defaults(),
                List.of(),
                new InMemoryOwnerRepairOpsAuditLog(),
                new InMemoryOwnerRepairOpsAuditLog(),
                new OpsHttpSecurityConfig("secret", "X-Ops-Token", "X-Ops-Operator"),
                List.of(incidents),
                List.of(slowTasks)
        )) {
            server.start();

            HttpResult unauth = get(server, "/actor-incidents");
            HttpResult auth = get(server, "/actor-incidents", "secret", null);
            HttpResult unauthMailboxes = get(server, "/actor-mailboxes");
            HttpResult authMailboxes = get(server, "/actor-mailboxes", "secret", null);
            HttpResult unauthSlowTasks = get(server, "/actor-slow-tasks");
            HttpResult authSlowTasks = get(server, "/actor-slow-tasks", "secret", null);
            HttpResult unauthHotspots = get(server, "/actor-hotspots");
            HttpResult authHotspots = get(server, "/actor-hotspots", "secret", null);

            assertEquals(401, unauth.statusCode());
            assertEquals("{\"error\":\"unauthorized\"}", unauth.body());
            assertEquals(200, auth.statusCode());
            assertTrue(auth.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertEquals(401, unauthMailboxes.statusCode());
            assertEquals("{\"error\":\"unauthorized\"}", unauthMailboxes.body());
            assertEquals(200, authMailboxes.statusCode());
            assertTrue(authMailboxes.body().contains("\"matched\":0,\"offset\":0,\"limit\":128"));
            assertEquals(401, unauthSlowTasks.statusCode());
            assertEquals("{\"error\":\"unauthorized\"}", unauthSlowTasks.body());
            assertEquals(200, authSlowTasks.statusCode());
            assertTrue(authSlowTasks.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
            assertEquals(401, unauthHotspots.statusCode());
            assertEquals("{\"error\":\"unauthorized\"}", unauthHotspots.body());
            assertEquals(200, authHotspots.statusCode());
            assertTrue(authHotspots.body().contains("\"matched\":1,\"offset\":0,\"limit\":128"));
        }
    }

    private static RuntimeHealthProbe probe(ActorSystem actors) {
        return probe(actors, null, null, null);
    }

    private static RuntimeHealthProbe probe(ActorSystem actors, OwnerRepairOpsAuditView ownerRepairOpsAudit) {
        return probe(actors, ownerRepairOpsAudit, null, null);
    }

    private static RuntimeHealthProbe probe(
            ActorSystem actors,
            OwnerRepairOpsAuditView ownerRepairOpsAudit,
            ActorIncidentView actorIncidents
    ) {
        return probe(actors, ownerRepairOpsAudit, actorIncidents, null);
    }

    private static RuntimeHealthProbe probe(
            ActorSystem actors,
            OwnerRepairOpsAuditView ownerRepairOpsAudit,
            ActorIncidentView actorIncidents,
            ActorSlowTaskView actorSlowTasks
    ) {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        if (ownerRepairOpsAudit != null) {
            registry.register(ownerRepairOpsAudit);
        }
        if (actorIncidents != null) {
            registry.register(actorIncidents);
        }
        if (actorSlowTasks != null) {
            registry.register(actorSlowTasks);
        }
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        return new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(local, actors, new InMemoryAgentDirectory(), CLOCK),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                registry,
                RuntimeHealthPolicy.defaults()
        );
    }

    private static HttpResult get(OpsHttpServer server, String path) throws Exception {
        return get(server, path, null, null);
    }

    private static HttpResult get(OpsHttpServer server, String path, String token, String operator) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .GET();
        addOpsHeaders(builder, token, operator);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return new HttpResult(response.statusCode(), contentType(response), response.body());
    }

    private static HttpResult post(OpsHttpServer server, String path) throws Exception {
        return post(server, path, null, null);
    }

    private static HttpResult post(OpsHttpServer server, String path, String token, String operator) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        addOpsHeaders(builder, token, operator);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return new HttpResult(response.statusCode(), contentType(response), response.body());
    }

    private static void addOpsHeaders(HttpRequest.Builder builder, String token, String operator) {
        if (token != null) {
            builder.header("X-Ops-Token", token);
        }
        if (operator != null) {
            builder.header("X-Ops-Operator", operator);
        }
    }

    private static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    private record HttpResult(int statusCode, String contentType, String body) {
    }

    private static final class RecordingHotspotMigrationAdmin implements ActorHotspotMigrationAdmin {
        private final List<ActorHotspotCandidate> candidates = new ArrayList<>();

        @Override
        public List<ActorHotspotMigrationResult> submitHotspotMigrations(
                List<ActorHotspotCandidate> candidates,
                int maxSubmissions
        ) {
            this.candidates.addAll(candidates);
            return candidates.stream()
                    .limit(maxSubmissions)
                    .map(candidate -> ActorHotspotMigrationResult.of(
                            candidate.actorId(),
                            Optional.empty(),
                            Optional.empty(),
                            ActorHotspotMigrationStatus.SUBMITTED,
                            ""
                    ))
                    .toList();
        }
    }

    private static final class RecordingRepairIsolationAdmin implements OwnerEventRepairIsolationAdmin {
        private final List<OwnerEventRepairIsolatedOwner> owners = new ArrayList<>();

        @Override
        public List<OwnerEventRepairIsolatedOwner> isolatedOwners() {
            return List.copyOf(owners);
        }

        @Override
        public int releaseIsolatedOwner(String ownerKey) {
            boolean removed = owners.removeIf(owner -> owner.ownerKey().equals(ownerKey));
            return removed ? 1 : 0;
        }

        @Override
        public int releaseAllIsolatedOwners() {
            int size = owners.size();
            owners.clear();
            return size;
        }
    }
}
