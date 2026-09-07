package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorOverflowStrategy;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterNodeConfigTest {
    @Test
    void classpathDefaultConfigsAreValidForTheirServiceKind() {
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/center.properties")
                .validate(ServiceKind.CENTER).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/game.properties")
                .validate(ServiceKind.GAME).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/region.properties")
                .validate(ServiceKind.REGION).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/proxy.properties")
                .validate(ServiceKind.PROXY).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/scene-small.properties")
                .validate(ServiceKind.SCENE).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/scene-large.properties")
                .validate(ServiceKind.SCENE).throwIfInvalid());
    }

    @Test
    void validationCollectsAllObviousConfigIssues() {
        Properties properties = base();
        properties.setProperty("cluster.kind", "GAME");
        properties.setProperty("cluster.port", "bad-port");
        properties.remove("cluster.center.host");
        properties.setProperty("scene.mode", "UNKNOWN");
        properties.setProperty("scene.capacity", "0");
        properties.setProperty("scene.shards", "-1");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.SCENE);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.kind"));
        assertTrue(keys.contains("cluster.port"));
        assertTrue(keys.contains("cluster.center.host"));
        assertTrue(keys.contains("scene.mode"));
        assertTrue(keys.contains("scene.capacity"));
        assertTrue(keys.contains("scene.shards"));
    }

    @Test
    void validationThrowsReadableErrorMessage() {
        Properties properties = base();
        properties.setProperty("cluster.port", "70000");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                validation::throwIfInvalid
        );
        assertTrue(error.getMessage().contains("cluster.port"));
    }

    @Test
    void configWarmupTimeoutCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.config.warmup.timeout.millis", "2500");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        org.junit.jupiter.api.Assertions.assertEquals(2500, config.configWarmupTimeout().toMillis());
    }

    @Test
    void actorSystemConfigCanBeConfiguredWithCategoryCapacities() {
        Properties properties = base();
        properties.setProperty("cluster.actor.batch.size", "32");
        properties.setProperty("cluster.actor.mailbox.capacity", "100");
        properties.setProperty("cluster.actor.overflow.strategy", "DROP_OLDEST");
        properties.setProperty("cluster.actor.shutdown.timeout.millis", "1500");
        properties.setProperty("cluster.actor.category.PLAYER_COMMAND.capacity", "60");
        properties.setProperty("cluster.actor.category.RPC_CALLBACK.capacity", "30");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        org.junit.jupiter.api.Assertions.assertEquals(4, config.actorSystemConfig().workerThreads());
        org.junit.jupiter.api.Assertions.assertEquals(32, config.actorSystemConfig().batchSize());
        org.junit.jupiter.api.Assertions.assertEquals(100, config.actorSystemConfig().mailboxCapacity());
        org.junit.jupiter.api.Assertions.assertEquals(ActorOverflowStrategy.DROP_OLDEST,
                config.actorSystemConfig().overflowStrategy());
        org.junit.jupiter.api.Assertions.assertEquals(1500, config.actorSystemConfig().shutdownTimeout().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(60,
                config.actorSystemConfig().categoryCapacities().get(ActorTaskCategory.PLAYER_COMMAND));
        org.junit.jupiter.api.Assertions.assertEquals(30,
                config.actorSystemConfig().categoryCapacities().get(ActorTaskCategory.RPC_CALLBACK));
    }

    @Test
    void runtimeHealthPolicyCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.health.max.queued.tasks", "200");
        properties.setProperty("cluster.health.max.pending.outbox.events", "3");
        properties.setProperty("cluster.health.max.migration.pending.age.millis", "60000");
        properties.setProperty("cluster.health.max.scene.active.scenes", "180");
        properties.setProperty("cluster.health.max.scene.active.players", "3000");
        properties.setProperty("cluster.health.max.scene.shard.hotspot.players", "500");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals(200, config.runtimeHealthPolicy().maxQueuedTasks());
        assertEquals(3, config.runtimeHealthPolicy().maxPendingOutboxEvents());
        assertEquals(60_000, config.runtimeHealthPolicy().maxMigrationPendingTaskAgeMillis());
        assertEquals(180, config.runtimeHealthPolicy().maxSceneActiveScenes());
        assertEquals(3000, config.runtimeHealthPolicy().maxSceneActivePlayers());
        assertEquals(500, config.runtimeHealthPolicy().maxSceneShardHotspotPlayers());
    }

    @Test
    void drainConfigCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.drain.timeout.millis", "45000");
        properties.setProperty("cluster.drain.poll.interval.millis", "25");
        properties.setProperty("cluster.drain.propagation.delay.millis", "300");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals(45_000, config.drainConfig().timeout().toMillis());
        assertEquals(25, config.drainConfig().pollInterval().toMillis());
        assertEquals(300, config.drainConfig().propagationDelay().toMillis());
    }

    @Test
    void playerCommandRateAndServerOpenTimeCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.player.command.rate.capacity", "800");
        properties.setProperty("cluster.player.command.rate.refill.permits", "400");
        properties.setProperty("cluster.player.command.rate.refill.interval.millis", "500");
        properties.setProperty("cluster.player.state.store", "FILE");
        properties.setProperty("cluster.player.state.store.dir", "data/custom-player-state");
        properties.setProperty("cluster.player.auto.save.enabled", "false");
        properties.setProperty("cluster.player.auto.save.initial.delay.millis", "15000");
        properties.setProperty("cluster.player.auto.save.interval.millis", "45000");
        properties.setProperty("game.server.open.time", "2026-08-01T00:00:00Z");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals(800, config.playerCommandRateLimitPolicy().capacity());
        assertEquals(400, config.playerCommandRateLimitPolicy().refillPermits());
        assertEquals(500, config.playerCommandRateLimitPolicy().refillInterval().toMillis());
        assertEquals(PlayerStateStoreKind.FILE, config.playerStateStoreKind());
        assertEquals(java.nio.file.Path.of("data/custom-player-state"), config.playerStateStoreDirectory());
        assertFalse(config.playerAutoSaveEnabled());
        assertEquals(15_000, config.playerAutoSaveInitialDelay().toMillis());
        assertEquals(45_000, config.playerAutoSaveInterval().toMillis());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), config.gameServerOpenTime());
    }

    @Test
    void validationRejectsInvalidActorConfig() {
        Properties properties = base();
        properties.setProperty("cluster.actor.batch.size", "0");
        properties.setProperty("cluster.actor.mailbox.capacity", "-1");
        properties.setProperty("cluster.actor.overflow.strategy", "UNKNOWN");
        properties.setProperty("cluster.actor.category.BAD.capacity", "10");
        properties.setProperty("cluster.actor.category.TIMER.capacity", "bad");
        properties.setProperty("cluster.player.command.rate.capacity", "0");
        properties.setProperty("cluster.player.command.rate.refill.permits", "-1");
        properties.setProperty("cluster.player.command.rate.refill.interval.millis", "bad");
        properties.setProperty("cluster.player.state.store", "BAD");
        properties.setProperty("cluster.player.auto.save.enabled", "maybe");
        properties.setProperty("cluster.player.auto.save.initial.delay.millis", "-1");
        properties.setProperty("cluster.player.auto.save.interval.millis", "0");
        properties.setProperty("game.server.open.time", "not-time");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.actor.batch.size"));
        assertTrue(keys.contains("cluster.actor.mailbox.capacity"));
        assertTrue(keys.contains("cluster.actor.overflow.strategy"));
        assertTrue(keys.contains("cluster.actor.category.BAD.capacity"));
        assertTrue(keys.contains("cluster.actor.category.TIMER.capacity"));
        assertTrue(keys.contains("cluster.player.command.rate.capacity"));
        assertTrue(keys.contains("cluster.player.command.rate.refill.permits"));
        assertTrue(keys.contains("cluster.player.command.rate.refill.interval.millis"));
        assertTrue(keys.contains("cluster.player.state.store"));
        assertTrue(keys.contains("cluster.player.auto.save.enabled"));
        assertTrue(keys.contains("cluster.player.auto.save.initial.delay.millis"));
        assertTrue(keys.contains("cluster.player.auto.save.interval.millis"));
        assertTrue(keys.contains("game.server.open.time"));
    }

    @Test
    void validationRejectsInvalidHealthPolicyConfig() {
        Properties properties = base();
        properties.setProperty("cluster.health.max.queued.tasks", "-1");
        properties.setProperty("cluster.health.max.pending.outbox.events", "-1");
        properties.setProperty("cluster.health.max.migration.pending.age.millis", "bad");
        properties.setProperty("cluster.health.max.scene.active.scenes", "-1");
        properties.setProperty("cluster.health.max.scene.active.players", "bad");
        properties.setProperty("cluster.health.max.scene.shard.hotspot.players", "-1");
        properties.setProperty("cluster.drain.timeout.millis", "0");
        properties.setProperty("cluster.drain.poll.interval.millis", "-1");
        properties.setProperty("cluster.drain.propagation.delay.millis", "bad");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.health.max.queued.tasks"));
        assertTrue(keys.contains("cluster.health.max.pending.outbox.events"));
        assertTrue(keys.contains("cluster.health.max.migration.pending.age.millis"));
        assertTrue(keys.contains("cluster.health.max.scene.active.scenes"));
        assertTrue(keys.contains("cluster.health.max.scene.active.players"));
        assertTrue(keys.contains("cluster.health.max.scene.shard.hotspot.players"));
        assertTrue(keys.contains("cluster.drain.timeout.millis"));
        assertTrue(keys.contains("cluster.drain.poll.interval.millis"));
        assertTrue(keys.contains("cluster.drain.propagation.delay.millis"));
    }

    @Test
    void registryLeaseTimingCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.registry.lease.ttl.millis", "12000");
        properties.setProperty("cluster.registry.heartbeat.interval.millis", "3000");
        properties.setProperty("cluster.registry.lease.scan.interval.millis", "500");
        properties.setProperty("cluster.ops.host", "0.0.0.0");
        properties.setProperty("cluster.ops.port", "19101");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        org.junit.jupiter.api.Assertions.assertEquals(12000, config.registryLeaseTtl().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(3000, config.registryHeartbeatInterval().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(500, config.registryLeaseScanInterval().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals("0.0.0.0", config.opsEndpoint().host());
        org.junit.jupiter.api.Assertions.assertEquals(19101, config.opsEndpoint().port());
    }

    @Test
    void migrationTaskRetentionCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.migration.task.retention.enabled", "false");
        properties.setProperty("cluster.migration.task.store", "FILE");
        properties.setProperty("cluster.migration.task.store.dir", "data/custom-migration-tasks");
        properties.setProperty("cluster.migration.task.retention.millis", "3600000");
        properties.setProperty("cluster.migration.task.retention.scan.interval.millis", "30000");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertFalse(config.migrationTaskRetentionEnabled());
        assertEquals(AgentMigrationTaskStoreKind.FILE, config.migrationTaskStoreKind());
        assertEquals("data\\custom-migration-tasks", config.migrationTaskStoreDirectory().toString());
        assertEquals(3_600_000, config.migrationTaskRetention().toMillis());
        assertEquals(30_000, config.migrationTaskRetentionScanInterval().toMillis());
    }

    @Test
    void shopStockReservationRetentionCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.shop.stock.store", "ATOMIC_MEMORY");
        properties.setProperty("cluster.shop.stock.reservation.retention.enabled", "false");
        properties.setProperty("cluster.shop.stock.reservation.ttl.millis", "120000");
        properties.setProperty("cluster.shop.stock.reservation.scan.interval.millis", "10000");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals(ShopStockStoreKind.ATOMIC_MEMORY, config.shopStockStoreKind());
        assertFalse(config.shopStockReservationRetentionEnabled());
        assertEquals(120_000, config.shopStockReservationTtl().toMillis());
        assertEquals(10_000, config.shopStockReservationScanInterval().toMillis());
    }

    @Test
    void jdbcMigrationTaskStoreCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.migration.task.store", "JDBC");
        properties.setProperty("cluster.migration.task.jdbc.driver", "");
        properties.setProperty("cluster.migration.task.jdbc.url", "jdbc:vendor://127.0.0.1/game");
        properties.setProperty("cluster.migration.task.jdbc.user", "game");
        properties.setProperty("cluster.migration.task.jdbc.password", "secret");
        properties.setProperty("cluster.migration.task.jdbc.table", "game_migration_tasks");
        properties.setProperty("cluster.migration.task.jdbc.initialize.schema", "false");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals(AgentMigrationTaskStoreKind.JDBC, config.migrationTaskStoreKind());
        assertEquals("", config.migrationTaskJdbcDriver());
        assertEquals("jdbc:vendor://127.0.0.1/game", config.migrationTaskJdbcUrl());
        assertEquals("game", config.migrationTaskJdbcUser());
        assertEquals("secret", config.migrationTaskJdbcPassword());
        assertEquals("game_migration_tasks", config.migrationTaskJdbcTable());
        assertFalse(config.migrationTaskJdbcInitializeSchema());
    }

    @Test
    void jdbcEventOutboxCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.event.outbox.store", "JDBC");
        properties.setProperty("cluster.event.outbox.jdbc.driver", "");
        properties.setProperty("cluster.event.outbox.jdbc.url", "jdbc:vendor://127.0.0.1/game");
        properties.setProperty("cluster.event.outbox.jdbc.user", "game");
        properties.setProperty("cluster.event.outbox.jdbc.password", "secret");
        properties.setProperty("cluster.event.outbox.jdbc.table", "game_event_outbox");
        properties.setProperty("cluster.event.outbox.jdbc.sequence.table", "game_event_outbox_seq");
        properties.setProperty("cluster.event.outbox.jdbc.initialize.schema", "false");
        properties.setProperty("cluster.event.outbox.replay.enabled", "false");
        properties.setProperty("cluster.event.outbox.replay.interval.millis", "7000");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals(EventOutboxStoreKind.JDBC, config.eventOutboxStoreKind());
        assertEquals("", config.eventOutboxJdbcDriver());
        assertEquals("jdbc:vendor://127.0.0.1/game", config.eventOutboxJdbcUrl());
        assertEquals("game", config.eventOutboxJdbcUser());
        assertEquals("secret", config.eventOutboxJdbcPassword());
        assertEquals("game_event_outbox", config.eventOutboxJdbcTable());
        assertEquals("game_event_outbox_seq", config.eventOutboxJdbcSequenceTable());
        assertFalse(config.eventOutboxJdbcInitializeSchema());
        assertFalse(config.eventOutboxReplayEnabled());
        assertEquals(7_000, config.eventOutboxReplayInterval().toMillis());
    }

    @Test
    void migrationRecoveryCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.migration.recovery.enabled", "false");
        properties.setProperty("cluster.migration.recovery.scan.interval.millis", "7000");
        properties.setProperty("cluster.migration.recovery.lease.ttl.millis", "45000");
        properties.setProperty("cluster.migration.recovery.target.accept.attempts", "3");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertFalse(config.migrationRecoveryEnabled());
        assertEquals(7_000, config.migrationRecoveryScanInterval().toMillis());
        assertEquals(45_000, config.migrationRecoveryLeaseTtl().toMillis());
        assertEquals(3, config.migrationPolicy().targetAcceptAttempts());
    }

    @Test
    void validationRejectsInvalidMigrationTaskRetentionConfig() {
        Properties properties = base();
        properties.setProperty("cluster.migration.task.store", "REDIS");
        properties.setProperty("cluster.migration.task.retention.enabled", "maybe");
        properties.setProperty("cluster.migration.task.retention.millis", "0");
        properties.setProperty("cluster.migration.task.retention.scan.interval.millis", "-1");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.migration.task.store"));
        assertTrue(keys.contains("cluster.migration.task.retention.enabled"));
        assertTrue(keys.contains("cluster.migration.task.retention.millis"));
        assertTrue(keys.contains("cluster.migration.task.retention.scan.interval.millis"));
    }

    @Test
    void validationRejectsInvalidShopStockReservationRetentionConfig() {
        Properties properties = base();
        properties.setProperty("cluster.shop.stock.store", "BAD");
        properties.setProperty("cluster.shop.stock.reservation.retention.enabled", "maybe");
        properties.setProperty("cluster.shop.stock.reservation.ttl.millis", "0");
        properties.setProperty("cluster.shop.stock.reservation.scan.interval.millis", "-1");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.CENTER);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.shop.stock.store"));
        assertTrue(keys.contains("cluster.shop.stock.reservation.retention.enabled"));
        assertTrue(keys.contains("cluster.shop.stock.reservation.ttl.millis"));
        assertTrue(keys.contains("cluster.shop.stock.reservation.scan.interval.millis"));
    }

    @Test
    void validationRejectsInvalidJdbcMigrationTaskStoreConfig() {
        Properties properties = base();
        properties.setProperty("cluster.migration.task.store", "JDBC");
        properties.setProperty("cluster.migration.task.jdbc.table", "task;drop");
        properties.setProperty("cluster.migration.task.jdbc.initialize.schema", "maybe");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.migration.task.jdbc.url"));
        assertTrue(keys.contains("cluster.migration.task.jdbc.table"));
        assertTrue(keys.contains("cluster.migration.task.jdbc.initialize.schema"));
    }

    @Test
    void validationRejectsInvalidEventOutboxConfig() {
        Properties properties = base();
        properties.setProperty("cluster.event.outbox.store", "BAD");
        properties.setProperty("cluster.event.outbox.jdbc.initialize.schema", "maybe");
        properties.setProperty("cluster.event.outbox.replay.enabled", "maybe");
        properties.setProperty("cluster.event.outbox.replay.interval.millis", "0");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.event.outbox.store"));
        assertTrue(keys.contains("cluster.event.outbox.jdbc.initialize.schema"));
        assertTrue(keys.contains("cluster.event.outbox.replay.enabled"));
        assertTrue(keys.contains("cluster.event.outbox.replay.interval.millis"));
    }

    @Test
    void validationRejectsInvalidJdbcEventOutboxConfig() {
        Properties properties = base();
        properties.setProperty("cluster.event.outbox.store", "JDBC");
        properties.setProperty("cluster.event.outbox.jdbc.table", "outbox;drop");
        properties.setProperty("cluster.event.outbox.jdbc.sequence.table", "seq;drop");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.event.outbox.jdbc.url"));
        assertTrue(keys.contains("cluster.event.outbox.jdbc.table"));
        assertTrue(keys.contains("cluster.event.outbox.jdbc.sequence.table"));
    }

    @Test
    void validationRejectsInvalidMigrationRecoveryConfig() {
        Properties properties = base();
        properties.setProperty("cluster.migration.recovery.enabled", "maybe");
        properties.setProperty("cluster.migration.recovery.scan.interval.millis", "0");
        properties.setProperty("cluster.migration.recovery.lease.ttl.millis", "-1");
        properties.setProperty("cluster.migration.recovery.target.accept.attempts", "bad");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.migration.recovery.enabled"));
        assertTrue(keys.contains("cluster.migration.recovery.scan.interval.millis"));
        assertTrue(keys.contains("cluster.migration.recovery.lease.ttl.millis"));
        assertTrue(keys.contains("cluster.migration.recovery.target.accept.attempts"));
    }

    @Test
    void eventHistoryPolicyCanBeConfiguredPerTopic() {
        Properties properties = base();
        properties.setProperty("cluster.event.history.default.limit", "128");
        properties.setProperty("cluster.event.history.topic.profile.changed.limit", "256");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        org.junit.jupiter.api.Assertions.assertEquals(128, config.eventHistoryPolicy().defaultLimit());
        org.junit.jupiter.api.Assertions.assertEquals(256, config.eventHistoryPolicy().limitOf("profile.changed"));
        org.junit.jupiter.api.Assertions.assertEquals(128, config.eventHistoryPolicy().limitOf("alliance.member.changed"));
    }

    @Test
    void validationRejectsInvalidEventHistoryTopicLimit() {
        Properties properties = base();
        properties.setProperty("cluster.event.history.default.limit", "0");
        properties.setProperty("cluster.event.history.topic.profile.changed.limit", "-1");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.event.history.default.limit"));
        assertTrue(keys.contains("cluster.event.history.topic.profile.changed.limit"));
    }

    private static Properties base() {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "GAME");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "game-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9001");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "4");
        return properties;
    }
}
