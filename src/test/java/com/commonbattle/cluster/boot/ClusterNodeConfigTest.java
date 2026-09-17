package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorOverflowStrategy;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.player.PlayerGatewayDuplicateLoginPolicy;
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
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/chat.properties")
                .validate(ServiceKind.CHAT).throwIfInvalid());
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
        properties.setProperty("scene.tick.enabled", "maybe");
        properties.setProperty("scene.tick.initial.delay.millis", "-1");
        properties.setProperty("scene.tick.interval.millis", "0");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.SCENE);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.kind"));
        assertTrue(keys.contains("cluster.port"));
        assertTrue(keys.contains("cluster.center.host"));
        assertTrue(keys.contains("scene.mode"));
        assertTrue(keys.contains("scene.capacity"));
        assertTrue(keys.contains("scene.shards"));
        assertTrue(keys.contains("scene.tick.enabled"));
        assertTrue(keys.contains("scene.tick.initial.delay.millis"));
        assertTrue(keys.contains("scene.tick.interval.millis"));
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
        properties.setProperty("cluster.actor.slow.task.threshold.millis", "25");
        properties.setProperty("cluster.actor.category.PLAYER_COMMAND.capacity", "60");
        properties.setProperty("cluster.actor.category.RPC_CALLBACK.capacity", "30");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        org.junit.jupiter.api.Assertions.assertEquals(4, config.actorSystemConfig().workerThreads());
        org.junit.jupiter.api.Assertions.assertEquals(32, config.actorSystemConfig().batchSize());
        org.junit.jupiter.api.Assertions.assertEquals(100, config.actorSystemConfig().mailboxCapacity());
        org.junit.jupiter.api.Assertions.assertEquals(ActorOverflowStrategy.DROP_OLDEST,
                config.actorSystemConfig().overflowStrategy());
        org.junit.jupiter.api.Assertions.assertEquals(1500, config.actorSystemConfig().shutdownTimeout().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(25, config.actorSystemConfig().slowTaskThreshold().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(60,
                config.actorSystemConfig().categoryCapacities().get(ActorTaskCategory.PLAYER_COMMAND));
        org.junit.jupiter.api.Assertions.assertEquals(30,
                config.actorSystemConfig().categoryCapacities().get(ActorTaskCategory.RPC_CALLBACK));
    }

    @Test
    void sceneTickConfigCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.kind", "SCENE");
        properties.setProperty("scene.tick.enabled", "true");
        properties.setProperty("scene.tick.initial.delay.millis", "250");
        properties.setProperty("scene.tick.interval.millis", "50");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertTrue(config.sceneTickEnabled());
        assertEquals(250, config.sceneTickInitialDelay().toMillis());
        assertEquals(50, config.sceneTickInterval().toMillis());
    }

    @Test
    void sceneTickConfigDefaultsToDisabled() {
        Properties properties = base();
        properties.setProperty("cluster.kind", "SCENE");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertFalse(config.sceneTickEnabled());
        assertEquals(1_000, config.sceneTickInitialDelay().toMillis());
        assertEquals(100, config.sceneTickInterval().toMillis());
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
        properties.setProperty("cluster.health.max.player.business.pending.responses", "900");
        properties.setProperty("cluster.health.max.player.business.pending.age.millis", "7000");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals(200, config.runtimeHealthPolicy().maxQueuedTasks());
        assertEquals(3, config.runtimeHealthPolicy().maxPendingOutboxEvents());
        assertEquals(60_000, config.runtimeHealthPolicy().maxMigrationPendingTaskAgeMillis());
        assertEquals(180, config.runtimeHealthPolicy().maxSceneActiveScenes());
        assertEquals(3000, config.runtimeHealthPolicy().maxSceneActivePlayers());
        assertEquals(500, config.runtimeHealthPolicy().maxSceneShardHotspotPlayers());
        assertEquals(900, config.runtimeHealthPolicy().maxPlayerBusinessPendingResponses());
        assertEquals(7000, config.runtimeHealthPolicy().maxPlayerBusinessPendingResponseAgeMillis());
    }

    @Test
    void clientGatewayCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.client.enabled", "true");
        properties.setProperty("cluster.client.host", "0.0.0.0");
        properties.setProperty("cluster.client.port", "29001");
        properties.setProperty("cluster.client.heartbeat.ack.enabled", "false");
        properties.setProperty("cluster.client.reader.idle.timeout.millis", "45000");
        properties.setProperty("cluster.client.duplicate.login.policy", "REJECT_NEW");
        properties.setProperty("cluster.client.command.rate.capacity", "20");
        properties.setProperty("cluster.client.command.rate.refill.permits", "10");
        properties.setProperty("cluster.client.command.rate.refill.interval.millis", "500");
        properties.setProperty("cluster.client.heartbeat.rate.capacity", "8");
        properties.setProperty("cluster.client.heartbeat.rate.refill.permits", "4");
        properties.setProperty("cluster.client.heartbeat.rate.refill.interval.millis", "1000");
        properties.setProperty("cluster.client.outbound.pending.ack.max.messages", "32");
        properties.setProperty("cluster.client.outbound.pending.ack.max.age.millis", "15000");
        properties.setProperty("cluster.client.outbound.slow.close.enabled", "false");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertTrue(config.clientGatewayEnabled());
        assertEquals("0.0.0.0", config.clientGatewayEndpoint().host());
        assertEquals(29001, config.clientGatewayEndpoint().port());
        assertFalse(config.playerGatewayConfig().heartbeatAckEnabled());
        assertEquals(45_000, config.playerGatewayConfig().readerIdleTimeout().toMillis());
        assertEquals(PlayerGatewayDuplicateLoginPolicy.REJECT_NEW, config.playerGatewayConfig().duplicateLoginPolicy());
        assertEquals(20, config.playerGatewayConfig().commandRateLimit().capacity());
        assertEquals(10, config.playerGatewayConfig().commandRateLimit().refillPermits());
        assertEquals(500, config.playerGatewayConfig().commandRateLimit().refillInterval().toMillis());
        assertEquals(8, config.playerGatewayConfig().heartbeatRateLimit().capacity());
        assertEquals(4, config.playerGatewayConfig().heartbeatRateLimit().refillPermits());
        assertEquals(1000, config.playerGatewayConfig().heartbeatRateLimit().refillInterval().toMillis());
        assertEquals(32, config.playerGatewayConfig().maxPendingAckMessages());
        assertEquals(15_000, config.playerGatewayConfig().maxPendingAckAge().toMillis());
        assertFalse(config.playerGatewayConfig().closeSlowClient());
    }

    @Test
    void clientGatewayUsesGameEndpointDefaults() {
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(base());

        assertFalse(config.clientGatewayEnabled());
        assertEquals("127.0.0.1", config.clientGatewayEndpoint().host());
        assertEquals(29001, config.clientGatewayEndpoint().port());
        assertTrue(config.playerGatewayConfig().heartbeatAckEnabled());
        assertEquals(0, config.playerGatewayConfig().readerIdleTimeout().toMillis());
        assertEquals(PlayerGatewayDuplicateLoginPolicy.KICK_OLD, config.playerGatewayConfig().duplicateLoginPolicy());
        assertEquals(200, config.playerGatewayConfig().commandRateLimit().capacity());
        assertEquals(60, config.playerGatewayConfig().heartbeatRateLimit().capacity());
        assertEquals(512, config.playerGatewayConfig().maxPendingAckMessages());
        assertEquals(30_000, config.playerGatewayConfig().maxPendingAckAge().toMillis());
        assertTrue(config.playerGatewayConfig().closeSlowClient());
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
        properties.setProperty("cluster.player.command.mailbox.pressure.enabled", "true");
        properties.setProperty("cluster.player.command.mailbox.pressure.target.max.queued", "300");
        properties.setProperty("cluster.player.command.mailbox.pressure.group.max.queued", "3000");
        properties.setProperty("cluster.player.command.mailbox.pressure.retry.after.millis", "75");
        properties.setProperty("cluster.business.agent.mailbox.pressure.enabled", "true");
        properties.setProperty("cluster.business.agent.mailbox.pressure.target.max.queued", "120");
        properties.setProperty("cluster.business.agent.mailbox.pressure.group.max.queued", "1200");
        properties.setProperty("cluster.business.agent.mailbox.pressure.retry.after.millis", "80");
        properties.setProperty("cluster.player.state.store", "FILE");
        properties.setProperty("cluster.player.state.store.dir", "data/custom-player-state");
        properties.setProperty("cluster.player.auto.save.enabled", "false");
        properties.setProperty("cluster.player.auto.save.initial.delay.millis", "15000");
        properties.setProperty("cluster.player.auto.save.interval.millis", "45000");
        properties.setProperty("cluster.player.business.response.timeout.millis", "3500");
        properties.setProperty("cluster.player.growth.stamina.recovery.enabled", "true");
        properties.setProperty("cluster.player.growth.stamina.recovery.initial.delay.millis", "3000");
        properties.setProperty("cluster.player.growth.stamina.recovery.interval.millis", "15000");
        properties.setProperty("game.server.open.time", "2026-08-01T00:00:00Z");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals(800, config.playerCommandRateLimitPolicy().capacity());
        assertEquals(400, config.playerCommandRateLimitPolicy().refillPermits());
        assertEquals(500, config.playerCommandRateLimitPolicy().refillInterval().toMillis());
        assertTrue(config.playerCommandMailboxPressurePolicy().enabled());
        assertEquals(300, config.playerCommandMailboxPressurePolicy().maxTargetQueuedTasks());
        assertEquals(3000, config.playerCommandMailboxPressurePolicy().maxGroupQueuedTasks());
        assertEquals(75, config.playerCommandMailboxPressurePolicy().retryAfter().toMillis());
        assertTrue(config.businessAgentMailboxPressurePolicy().enabled());
        assertEquals(120, config.businessAgentMailboxPressurePolicy().maxTargetQueuedTasks());
        assertEquals(1200, config.businessAgentMailboxPressurePolicy().maxGroupQueuedTasks());
        assertEquals(80, config.businessAgentMailboxPressurePolicy().retryAfter().toMillis());
        assertEquals(PlayerStateStoreKind.FILE, config.playerStateStoreKind());
        assertEquals(java.nio.file.Path.of("data/custom-player-state"), config.playerStateStoreDirectory());
        assertFalse(config.playerAutoSaveEnabled());
        assertEquals(15_000, config.playerAutoSaveInitialDelay().toMillis());
        assertEquals(45_000, config.playerAutoSaveInterval().toMillis());
        assertEquals(3_500, config.playerBusinessResponseTimeout().toMillis());
        assertTrue(config.playerGrowthStaminaRecoveryEnabled());
        assertEquals(3_000, config.playerGrowthStaminaRecoveryInitialDelay().toMillis());
        assertEquals(15_000, config.playerGrowthStaminaRecoveryInterval().toMillis());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), config.gameServerOpenTime());
    }

    @Test
    void validationRejectsInvalidActorConfig() {
        Properties properties = base();
        properties.setProperty("cluster.actor.batch.size", "0");
        properties.setProperty("cluster.actor.mailbox.capacity", "-1");
        properties.setProperty("cluster.actor.overflow.strategy", "UNKNOWN");
        properties.setProperty("cluster.actor.slow.task.threshold.millis", "-1");
        properties.setProperty("cluster.actor.category.BAD.capacity", "10");
        properties.setProperty("cluster.actor.category.TIMER.capacity", "bad");
        properties.setProperty("cluster.player.command.rate.capacity", "0");
        properties.setProperty("cluster.player.command.rate.refill.permits", "-1");
        properties.setProperty("cluster.player.command.rate.refill.interval.millis", "bad");
        properties.setProperty("cluster.player.state.store", "BAD");
        properties.setProperty("cluster.player.auto.save.enabled", "maybe");
        properties.setProperty("cluster.player.auto.save.initial.delay.millis", "-1");
        properties.setProperty("cluster.player.auto.save.interval.millis", "0");
        properties.setProperty("cluster.player.growth.stamina.recovery.enabled", "maybe");
        properties.setProperty("cluster.player.growth.stamina.recovery.initial.delay.millis", "-1");
        properties.setProperty("cluster.player.growth.stamina.recovery.interval.millis", "0");
        properties.setProperty("cluster.client.enabled", "maybe");
        properties.setProperty("cluster.client.port", "0");
        properties.setProperty("cluster.client.heartbeat.ack.enabled", "maybe");
        properties.setProperty("cluster.client.reader.idle.timeout.millis", "-1");
        properties.setProperty("cluster.client.duplicate.login.policy", "BAD");
        properties.setProperty("cluster.client.command.rate.capacity", "0");
        properties.setProperty("cluster.client.command.rate.refill.permits", "-1");
        properties.setProperty("cluster.client.command.rate.refill.interval.millis", "bad");
        properties.setProperty("cluster.client.heartbeat.rate.capacity", "0");
        properties.setProperty("cluster.client.heartbeat.rate.refill.permits", "-1");
        properties.setProperty("cluster.client.heartbeat.rate.refill.interval.millis", "bad");
        properties.setProperty("cluster.client.outbound.pending.ack.max.messages", "0");
        properties.setProperty("cluster.client.outbound.pending.ack.max.age.millis", "-1");
        properties.setProperty("cluster.client.outbound.slow.close.enabled", "maybe");
        properties.setProperty("game.server.open.time", "not-time");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.actor.batch.size"));
        assertTrue(keys.contains("cluster.actor.mailbox.capacity"));
        assertTrue(keys.contains("cluster.actor.overflow.strategy"));
        assertTrue(keys.contains("cluster.actor.slow.task.threshold.millis"));
        assertTrue(keys.contains("cluster.actor.category.BAD.capacity"));
        assertTrue(keys.contains("cluster.actor.category.TIMER.capacity"));
        assertTrue(keys.contains("cluster.player.command.rate.capacity"));
        assertTrue(keys.contains("cluster.player.command.rate.refill.permits"));
        assertTrue(keys.contains("cluster.player.command.rate.refill.interval.millis"));
        assertTrue(keys.contains("cluster.player.state.store"));
        assertTrue(keys.contains("cluster.player.auto.save.enabled"));
        assertTrue(keys.contains("cluster.player.auto.save.initial.delay.millis"));
        assertTrue(keys.contains("cluster.player.auto.save.interval.millis"));
        assertTrue(keys.contains("cluster.player.growth.stamina.recovery.enabled"));
        assertTrue(keys.contains("cluster.player.growth.stamina.recovery.initial.delay.millis"));
        assertTrue(keys.contains("cluster.player.growth.stamina.recovery.interval.millis"));
        assertTrue(keys.contains("cluster.client.enabled"));
        assertTrue(keys.contains("cluster.client.port"));
        assertTrue(keys.contains("cluster.client.heartbeat.ack.enabled"));
        assertTrue(keys.contains("cluster.client.reader.idle.timeout.millis"));
        assertTrue(keys.contains("cluster.client.duplicate.login.policy"));
        assertTrue(keys.contains("cluster.client.command.rate.capacity"));
        assertTrue(keys.contains("cluster.client.command.rate.refill.permits"));
        assertTrue(keys.contains("cluster.client.command.rate.refill.interval.millis"));
        assertTrue(keys.contains("cluster.client.heartbeat.rate.capacity"));
        assertTrue(keys.contains("cluster.client.heartbeat.rate.refill.permits"));
        assertTrue(keys.contains("cluster.client.heartbeat.rate.refill.interval.millis"));
        assertTrue(keys.contains("cluster.client.outbound.pending.ack.max.messages"));
        assertTrue(keys.contains("cluster.client.outbound.pending.ack.max.age.millis"));
        assertTrue(keys.contains("cluster.client.outbound.slow.close.enabled"));
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
        properties.setProperty("cluster.registry.subscription.lease.ttl.millis", "11000");
        properties.setProperty("cluster.registry.subscription.lease.scan.interval.millis", "700");
        properties.setProperty("cluster.registry.history.limit", "64");
        properties.setProperty("cluster.registry.recovery.enabled", "false");
        properties.setProperty("cluster.registry.recovery.interval.millis", "7000");
        properties.setProperty("cluster.event.subscription.lease.ttl.millis", "13000");
        properties.setProperty("cluster.event.subscription.lease.renew.interval.millis", "4000");
        properties.setProperty("cluster.event.subscription.lease.scan.interval.millis", "900");
        properties.setProperty("cluster.event.repair.scheduler.enabled", "false");
        properties.setProperty("cluster.event.repair.dispatcher.enabled", "false");
        properties.setProperty("cluster.event.repair.dispatcher.interval.millis", "750");
        properties.setProperty("cluster.event.repair.dispatcher.max.drains.per.tick", "3");
        properties.setProperty("cluster.event.repair.interval.millis", "2500");
        properties.setProperty("cluster.event.repair.max.batch.size", "32");
        properties.setProperty("cluster.event.repair.priority", "3");
        properties.setProperty("cluster.event.repair.backoff.initial.millis", "100");
        properties.setProperty("cluster.event.repair.backoff.max.millis", "2000");
        properties.setProperty("cluster.event.repair.backoff.multiplier", "1.5");
        properties.setProperty("cluster.event.repair.owner.isolation.max.failures", "4");
        properties.setProperty("cluster.event.repair.owner.isolation.duration.millis", "30000");
        properties.setProperty("cluster.ops.host", "0.0.0.0");
        properties.setProperty("cluster.ops.port", "19101");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        org.junit.jupiter.api.Assertions.assertEquals(12000, config.registryLeaseTtl().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(3000, config.registryHeartbeatInterval().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(500, config.registryLeaseScanInterval().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(11000, config.registrySubscriptionLeaseTtl().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(700, config.registrySubscriptionLeaseScanInterval().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(64, config.registryHistoryLimit());
        assertFalse(config.registryRecoveryEnabled());
        org.junit.jupiter.api.Assertions.assertEquals(7000, config.registryRecoveryInterval().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(13000, config.eventSubscriptionLeaseTtl().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(4000, config.eventSubscriptionLeaseRenewInterval().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(900, config.eventSubscriptionLeaseScanInterval().toMillis());
        assertFalse(config.eventRepairSchedulerEnabled());
        assertFalse(config.eventRepairDispatcherEnabled());
        org.junit.jupiter.api.Assertions.assertEquals(750, config.eventRepairDispatcherInterval().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(3, config.eventRepairDispatcherMaxDrainsPerTick());
        org.junit.jupiter.api.Assertions.assertEquals(2500, config.eventRepairInterval().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(32, config.eventRepairMaxBatchSize());
        org.junit.jupiter.api.Assertions.assertEquals(3, config.eventRepairPriority());
        org.junit.jupiter.api.Assertions.assertEquals(100,
                config.eventRepairBackoffPolicy("friend.changed").initialDelay().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(2000,
                config.eventRepairBackoffPolicy("friend.changed").maxDelay().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals(1.5,
                config.eventRepairBackoffPolicy("friend.changed").multiplier(),
                0.0001);
        org.junit.jupiter.api.Assertions.assertEquals(4,
                config.eventRepairIsolationPolicy("friend.changed").maxFailures());
        org.junit.jupiter.api.Assertions.assertEquals(30000,
                config.eventRepairIsolationPolicy("friend.changed").duration().toMillis());
        org.junit.jupiter.api.Assertions.assertEquals("0.0.0.0", config.opsEndpoint().host());
        org.junit.jupiter.api.Assertions.assertEquals(19101, config.opsEndpoint().port());
    }

    @Test
    void eventRepairTopicOverridesInheritGlobalDefaults() {
        Properties properties = base();
        properties.setProperty("cluster.event.repair.scheduler.enabled", "true");
        properties.setProperty("cluster.event.repair.interval.millis", "5000");
        properties.setProperty("cluster.event.repair.max.batch.size", "64");
        properties.setProperty("cluster.event.repair.priority", "2");
        properties.setProperty("cluster.event.repair.backoff.initial.millis", "1000");
        properties.setProperty("cluster.event.repair.backoff.max.millis", "30000");
        properties.setProperty("cluster.event.repair.backoff.multiplier", "2.0");
        properties.setProperty("cluster.event.repair.owner.isolation.max.failures", "3");
        properties.setProperty("cluster.event.repair.owner.isolation.duration.millis", "60000");
        properties.setProperty("cluster.event.repair.topic.friend.changed.scheduler.enabled", "false");
        properties.setProperty("cluster.event.repair.topic.friend.changed.interval.millis", "2500");
        properties.setProperty("cluster.event.repair.topic.friend.changed.priority", "9");
        properties.setProperty("cluster.event.repair.topic.friend.changed.backoff.max.millis", "5000");
        properties.setProperty("cluster.event.repair.topic.friend.changed.owner.isolation.duration.millis", "10000");
        properties.setProperty("cluster.event.repair.topic.alliance.member.changed.max.batch.size", "128");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertFalse(config.eventRepairSchedulerEnabled("friend.changed"));
        assertEquals(2500, config.eventRepairInterval("friend.changed").toMillis());
        assertEquals(64, config.eventRepairMaxBatchSize("friend.changed"));
        assertEquals(9, config.eventRepairPriority("friend.changed"));
        assertEquals(1000, config.eventRepairBackoffPolicy("friend.changed").initialDelay().toMillis());
        assertEquals(5000, config.eventRepairBackoffPolicy("friend.changed").maxDelay().toMillis());
        assertEquals(3, config.eventRepairIsolationPolicy("friend.changed").maxFailures());
        assertEquals(10000, config.eventRepairIsolationPolicy("friend.changed").duration().toMillis());
        assertTrue(config.eventRepairSchedulerEnabled("alliance.member.changed"));
        assertEquals(5000, config.eventRepairInterval("alliance.member.changed").toMillis());
        assertEquals(128, config.eventRepairMaxBatchSize("alliance.member.changed"));
        assertEquals(2, config.eventRepairPriority("alliance.member.changed"));
        assertEquals(30000, config.eventRepairBackoffPolicy("alliance.member.changed").maxDelay().toMillis());
        assertEquals(60000, config.eventRepairIsolationPolicy("alliance.member.changed").duration().toMillis());
    }

    @Test
    void serviceMetadataCanBeConfiguredForRouting() {
        Properties properties = base();
        properties.setProperty("cluster.route.tag", "gray");
        properties.setProperty("cluster.deployment.group", "canary-1");
        properties.setProperty("cluster.metadata.zone.partition", "east");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals("gray", config.serviceMetadata().get(ServiceMetadata.ROUTE_TAG));
        assertEquals("canary-1", config.serviceMetadata().get(ServiceMetadata.DEPLOYMENT_GROUP));
        assertEquals("east", config.serviceMetadata().get("zone.partition"));
    }

    @Test
    void playerGrayRouteConfigCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.rpc.gray.enabled", "true");
        properties.setProperty("cluster.rpc.gray.stable.tag", "stable");
        properties.setProperty("cluster.rpc.gray.gray.tag", "gray");
        properties.setProperty("cluster.rpc.gray.percent", "15");
        properties.setProperty("cluster.rpc.gray.players", "10001,10002");
        properties.setProperty("cluster.rpc.gray.operations", "scene.enter,scene.leave");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertTrue(config.playerGrayRouteConfig().enabled());
        assertEquals("stable", config.playerGrayRouteConfig().stableTag());
        assertEquals("gray", config.playerGrayRouteConfig().grayTag());
        assertEquals(15, config.playerGrayRouteConfig().grayPercent());
        assertTrue(config.playerGrayRouteConfig().playerWhitelist().contains(10001L));
        assertTrue(config.playerGrayRouteConfig().operations().contains("scene.enter"));
    }

    @Test
    void chatRouteConfigCanBeConfigured() {
        Properties properties = base();
        properties.setProperty("cluster.kind", "CHAT");
        properties.setProperty("chat.world.shards", "16");
        properties.setProperty("chat.history.max.messages", "256");
        properties.setProperty("chat.delivery.max.pending.per.recipient", "512");
        properties.setProperty("chat.delivery.overflow.strategy", "DROP_NEWEST");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertEquals(16, config.chatRouteConfig().worldShardCount());
        assertEquals(256, config.chatRouteConfig().maxHistoryMessages());
        assertEquals(512, config.chatRouteConfig().maxPendingDeliveriesPerRecipient());
        assertEquals(com.commonbattle.game.chat.ChatDeliveryOverflowStrategy.DROP_NEWEST,
                config.chatRouteConfig().deliveryOverflowStrategy());
    }

    @Test
    void serviceMailboxPressurePoliciesCanBeConfiguredIndependently() {
        Properties properties = base();
        properties.setProperty("cluster.chat.mailbox.pressure.enabled", "true");
        properties.setProperty("cluster.chat.mailbox.pressure.target.max.queued", "300");
        properties.setProperty("cluster.chat.mailbox.pressure.group.max.queued", "3000");
        properties.setProperty("cluster.chat.mailbox.pressure.retry.after.millis", "75");
        properties.setProperty("cluster.scene.mailbox.pressure.enabled", "true");
        properties.setProperty("cluster.scene.mailbox.pressure.target.max.queued", "400");
        properties.setProperty("cluster.scene.mailbox.pressure.group.max.queued", "4000");
        properties.setProperty("cluster.scene.mailbox.pressure.retry.after.millis", "80");

        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        assertTrue(config.chatMailboxPressurePolicy().enabled());
        assertEquals(300, config.chatMailboxPressurePolicy().maxTargetQueuedTasks());
        assertEquals(3000, config.chatMailboxPressurePolicy().maxGroupQueuedTasks());
        assertEquals(75, config.chatMailboxPressurePolicy().retryAfter().toMillis());
        assertTrue(config.sceneMailboxPressurePolicy().enabled());
        assertEquals(400, config.sceneMailboxPressurePolicy().maxTargetQueuedTasks());
        assertEquals(4000, config.sceneMailboxPressurePolicy().maxGroupQueuedTasks());
        assertEquals(80, config.sceneMailboxPressurePolicy().retryAfter().toMillis());
    }

    @Test
    void validationRejectsInvalidServiceMetadataConfig() {
        Properties properties = base();
        properties.setProperty("cluster.route.tag", "gray");
        properties.setProperty("cluster.metadata.service.route.tag", "stable");
        properties.setProperty("cluster.metadata.", "bad");
        properties.setProperty("cluster.metadata.zone.partition", "");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.route.tag"));
        assertTrue(keys.contains("cluster.metadata."));
        assertTrue(keys.contains("cluster.metadata.zone.partition"));
    }

    @Test
    void validationRejectsInvalidPlayerGrayRouteConfig() {
        Properties properties = base();
        properties.setProperty("cluster.rpc.gray.enabled", "maybe");
        properties.setProperty("cluster.rpc.gray.stable.tag", "");
        properties.setProperty("cluster.rpc.gray.percent", "101");
        properties.setProperty("cluster.rpc.gray.players", "10001,bad");
        properties.setProperty("cluster.rpc.gray.operations", "scene.enter,,scene.leave");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.rpc.gray.enabled"));
        assertTrue(keys.contains("cluster.rpc.gray.stable.tag"));
        assertTrue(keys.contains("cluster.rpc.gray.percent"));
        assertTrue(keys.contains("cluster.rpc.gray.players"));
        assertTrue(keys.contains("cluster.rpc.gray.operations"));
    }

    @Test
    void validationRejectsInvalidChatRouteConfig() {
        Properties properties = base();
        properties.setProperty("cluster.kind", "CHAT");
        properties.setProperty("chat.world.shards", "0");
        properties.setProperty("chat.history.max.messages", "-1");
        properties.setProperty("chat.delivery.max.pending.per.recipient", "0");
        properties.setProperty("chat.delivery.overflow.strategy", "BAD");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.CHAT);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("chat.world.shards"));
        assertTrue(keys.contains("chat.history.max.messages"));
        assertTrue(keys.contains("chat.delivery.max.pending.per.recipient"));
        assertTrue(keys.contains("chat.delivery.overflow.strategy"));
    }

    @Test
    void validationRejectsInvalidRegistryRecoveryConfig() {
        Properties properties = base();
        properties.setProperty("cluster.registry.recovery.enabled", "maybe");
        properties.setProperty("cluster.registry.recovery.interval.millis", "0");
        properties.setProperty("cluster.registry.history.limit", "-1");
        properties.setProperty("cluster.registry.subscription.lease.ttl.millis", "0");
        properties.setProperty("cluster.registry.subscription.lease.scan.interval.millis", "0");
        properties.setProperty("cluster.event.subscription.lease.ttl.millis", "0");
        properties.setProperty("cluster.event.subscription.lease.renew.interval.millis", "0");
        properties.setProperty("cluster.event.subscription.lease.scan.interval.millis", "0");
        properties.setProperty("cluster.event.repair.scheduler.enabled", "maybe");
        properties.setProperty("cluster.event.repair.dispatcher.enabled", "maybe");
        properties.setProperty("cluster.event.repair.dispatcher.interval.millis", "0");
        properties.setProperty("cluster.event.repair.dispatcher.max.drains.per.tick", "0");
        properties.setProperty("cluster.event.repair.interval.millis", "0");
        properties.setProperty("cluster.event.repair.max.batch.size", "-1");
        properties.setProperty("cluster.event.repair.priority", "-1");
        properties.setProperty("cluster.event.repair.backoff.initial.millis", "-1");
        properties.setProperty("cluster.event.repair.backoff.max.millis", "bad");
        properties.setProperty("cluster.event.repair.backoff.multiplier", "0.5");
        properties.setProperty("cluster.event.repair.owner.isolation.max.failures", "-1");
        properties.setProperty("cluster.event.repair.owner.isolation.duration.millis", "-1");
        properties.setProperty("cluster.event.repair.topic.friend.changed.scheduler.enabled", "bad");
        properties.setProperty("cluster.event.repair.topic.friend.changed.interval.millis", "0");
        properties.setProperty("cluster.event.repair.topic.friend.changed.priority", "-1");
        properties.setProperty("cluster.event.repair.topic.friend.changed.backoff.initial.millis", "2000");
        properties.setProperty("cluster.event.repair.topic.friend.changed.backoff.max.millis", "1000");
        properties.setProperty("cluster.event.repair.topic.friend.changed.backoff.multiplier", "bad");
        properties.setProperty("cluster.event.repair.topic.friend.changed.owner.isolation.max.failures", "-1");
        properties.setProperty("cluster.event.repair.topic.friend.changed.owner.isolation.duration.millis", "-1");
        properties.setProperty("cluster.event.repair.topic.alliance.member.changed.max.batch.size", "-1");
        properties.setProperty("cluster.event.repair.topic..interval.millis", "1000");
        properties.setProperty("cluster.event.repair.topic.friend.changed.unknown", "1");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.registry.recovery.enabled"));
        assertTrue(keys.contains("cluster.registry.recovery.interval.millis"));
        assertTrue(keys.contains("cluster.registry.history.limit"));
        assertTrue(keys.contains("cluster.registry.subscription.lease.ttl.millis"));
        assertTrue(keys.contains("cluster.registry.subscription.lease.scan.interval.millis"));
        assertTrue(keys.contains("cluster.event.subscription.lease.ttl.millis"));
        assertTrue(keys.contains("cluster.event.subscription.lease.renew.interval.millis"));
        assertTrue(keys.contains("cluster.event.subscription.lease.scan.interval.millis"));
        assertTrue(keys.contains("cluster.event.repair.scheduler.enabled"));
        assertTrue(keys.contains("cluster.event.repair.dispatcher.enabled"));
        assertTrue(keys.contains("cluster.event.repair.dispatcher.interval.millis"));
        assertTrue(keys.contains("cluster.event.repair.dispatcher.max.drains.per.tick"));
        assertTrue(keys.contains("cluster.event.repair.interval.millis"));
        assertTrue(keys.contains("cluster.event.repair.max.batch.size"));
        assertTrue(keys.contains("cluster.event.repair.priority"));
        assertTrue(keys.contains("cluster.event.repair.backoff.initial.millis"));
        assertTrue(keys.contains("cluster.event.repair.backoff.max.millis"));
        assertTrue(keys.contains("cluster.event.repair.backoff.multiplier"));
        assertTrue(keys.contains("cluster.event.repair.owner.isolation.max.failures"));
        assertTrue(keys.contains("cluster.event.repair.owner.isolation.duration.millis"));
        assertTrue(keys.contains("cluster.event.repair.topic.friend.changed.scheduler.enabled"));
        assertTrue(keys.contains("cluster.event.repair.topic.friend.changed.interval.millis"));
        assertTrue(keys.contains("cluster.event.repair.topic.friend.changed.priority"));
        assertTrue(keys.contains("cluster.event.repair.topic.friend.changed.backoff.max.millis"));
        assertTrue(keys.contains("cluster.event.repair.topic.friend.changed.backoff.multiplier"));
        assertTrue(keys.contains("cluster.event.repair.topic.friend.changed.owner.isolation.max.failures"));
        assertTrue(keys.contains("cluster.event.repair.topic.friend.changed.owner.isolation.duration.millis"));
        assertTrue(keys.contains("cluster.event.repair.topic.alliance.member.changed.max.batch.size"));
        assertTrue(keys.contains("cluster.event.repair.topic..interval.millis"));
        assertTrue(keys.contains("cluster.event.repair.topic.friend.changed.unknown"));
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
