package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorOverflowStrategy;
import com.commonbattle.actor.ActorSystemConfig;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.backpressure.ActorMailboxPressurePolicy;
import com.commonbattle.actor.backpressure.AgentRateLimitPolicy;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.cluster.event.ClusterEventHistoryPolicy;
import com.commonbattle.cluster.rpc.PlayerGrayRouteConfig;
import com.commonbattle.game.chat.ChatRouteConfig;
import com.commonbattle.game.event.OwnerEventRepairBackoffPolicy;
import com.commonbattle.game.event.OwnerEventRepairIsolationPolicy;
import com.commonbattle.game.player.PlayerGatewayConfig;
import com.commonbattle.game.player.PlayerGatewayDuplicateLoginPolicy;
import com.commonbattle.observability.DrainConfig;
import com.commonbattle.example.cross.scene.SceneHostingMode;
import com.commonbattle.observability.RuntimeHealthPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * 独立部署进程使用的节点配置。
 * 配置可以来自外部 properties 文件，也可以来自 classpath 下的示例配置。
 */
public final class ClusterNodeConfig {
    private static final int DEFAULT_EVENT_REPAIR_MAX_BATCH_SIZE = 64;
    private final Properties properties;

    private ClusterNodeConfig(Properties properties) {
        this.properties = new Properties();
        this.properties.putAll(properties);
    }

    public static ClusterNodeConfig load(String[] args, String classpathDefault) {
        if (args.length > 0) {
            return fromPath(Path.of(args[0]));
        }
        return fromClasspath(classpathDefault);
    }

    public static ClusterNodeConfig fromPath(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            Properties properties = new Properties();
            properties.load(input);
            return new ClusterNodeConfig(properties);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config " + path, e);
        }
    }

    public static ClusterNodeConfig fromClasspath(String resource) {
        try (InputStream input = ClusterNodeConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing classpath config " + resource);
            }
            Properties properties = new Properties();
            properties.load(input);
            return new ClusterNodeConfig(properties);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load classpath config " + resource, e);
        }
    }

    public static ClusterNodeConfig fromProperties(Properties properties) {
        return new ClusterNodeConfig(Objects.requireNonNull(properties, "properties"));
    }

    public ClusterConfigValidation validate(ServiceKind expectedKind) {
        List<ClusterConfigIssue> issues = new ArrayList<>();
        require(issues, "cluster.kind");
        require(issues, "cluster.region");
        require(issues, "cluster.node");
        require(issues, "cluster.host");
        validatePort(issues, "cluster.port");
        validateOptionalPort(issues, "cluster.ops.port");
        validateBoolean(issues, "cluster.client.enabled");
        validateOptionalPort(issues, "cluster.client.port");
        validateBoolean(issues, "cluster.client.heartbeat.ack.enabled");
        validateNonNegativeInteger(issues, "cluster.client.reader.idle.timeout.millis");
        validateDuplicateLoginPolicy(issues);
        validatePositiveInteger(issues, "cluster.client.command.rate.capacity");
        validatePositiveInteger(issues, "cluster.client.command.rate.refill.permits");
        validatePositiveInteger(issues, "cluster.client.command.rate.refill.interval.millis");
        validatePositiveInteger(issues, "cluster.client.heartbeat.rate.capacity");
        validatePositiveInteger(issues, "cluster.client.heartbeat.rate.refill.permits");
        validatePositiveInteger(issues, "cluster.client.heartbeat.rate.refill.interval.millis");
        validatePositiveInteger(issues, "cluster.client.outbound.pending.ack.max.messages");
        validateNonNegativeInteger(issues, "cluster.client.outbound.pending.ack.max.age.millis");
        validateBoolean(issues, "cluster.client.outbound.slow.close.enabled");
        validatePositiveInteger(issues, "cluster.actor.workers");
        validatePositiveInteger(issues, "cluster.actor.batch.size");
        validatePositiveInteger(issues, "cluster.actor.mailbox.capacity");
        validatePositiveInteger(issues, "cluster.actor.shutdown.timeout.millis");
        validateNonNegativeInteger(issues, "cluster.actor.slow.task.threshold.millis");
        validatePositiveInteger(issues, "cluster.player.command.rate.capacity");
        validatePositiveInteger(issues, "cluster.player.command.rate.refill.permits");
        validatePositiveInteger(issues, "cluster.player.command.rate.refill.interval.millis");
        validateBoolean(issues, "cluster.player.command.mailbox.pressure.enabled");
        validateNonNegativeInteger(issues, "cluster.player.command.mailbox.pressure.target.max.queued");
        validateNonNegativeInteger(issues, "cluster.player.command.mailbox.pressure.group.max.queued");
        validateNonNegativeInteger(issues, "cluster.player.command.mailbox.pressure.retry.after.millis");
        validateBoolean(issues, "cluster.business.agent.mailbox.pressure.enabled");
        validateNonNegativeInteger(issues, "cluster.business.agent.mailbox.pressure.target.max.queued");
        validateNonNegativeInteger(issues, "cluster.business.agent.mailbox.pressure.group.max.queued");
        validateNonNegativeInteger(issues, "cluster.business.agent.mailbox.pressure.retry.after.millis");
        validateBoolean(issues, "cluster.chat.mailbox.pressure.enabled");
        validateNonNegativeInteger(issues, "cluster.chat.mailbox.pressure.target.max.queued");
        validateNonNegativeInteger(issues, "cluster.chat.mailbox.pressure.group.max.queued");
        validateNonNegativeInteger(issues, "cluster.chat.mailbox.pressure.retry.after.millis");
        validateBoolean(issues, "cluster.scene.mailbox.pressure.enabled");
        validateNonNegativeInteger(issues, "cluster.scene.mailbox.pressure.target.max.queued");
        validateNonNegativeInteger(issues, "cluster.scene.mailbox.pressure.group.max.queued");
        validateNonNegativeInteger(issues, "cluster.scene.mailbox.pressure.retry.after.millis");
        validatePositiveInteger(issues, "cluster.player.business.response.timeout.millis");
        validateBoolean(issues, "cluster.player.auto.save.enabled");
        validateNonNegativeInteger(issues, "cluster.player.auto.save.initial.delay.millis");
        validatePositiveInteger(issues, "cluster.player.auto.save.interval.millis");
        validateBoolean(issues, "cluster.player.growth.stamina.recovery.enabled");
        validateNonNegativeInteger(issues, "cluster.player.growth.stamina.recovery.initial.delay.millis");
        validatePositiveInteger(issues, "cluster.player.growth.stamina.recovery.interval.millis");
        validateInstant(issues, "game.server.open.time");
        validateActorOverflowStrategy(issues);
        validateActorCategoryCapacities(issues);
        validateNonNegativeInteger(issues, "cluster.health.max.queued.tasks");
        validateNonNegativeInteger(issues, "cluster.health.max.pending.outbox.events");
        validateNonNegativeInteger(issues, "cluster.health.max.migration.pending.age.millis");
        validateNonNegativeInteger(issues, "cluster.health.max.scene.active.scenes");
        validateNonNegativeInteger(issues, "cluster.health.max.scene.active.players");
        validateNonNegativeInteger(issues, "cluster.health.max.scene.shard.hotspot.players");
        validateNonNegativeInteger(issues, "cluster.health.max.player.business.pending.responses");
        validateNonNegativeInteger(issues, "cluster.health.max.player.business.pending.age.millis");
        validatePositiveInteger(issues, "cluster.drain.timeout.millis");
        validatePositiveInteger(issues, "cluster.drain.poll.interval.millis");
        validateNonNegativeInteger(issues, "cluster.drain.propagation.delay.millis");
        validateEventOutboxStoreKind(issues);
        validatePlayerStateStoreKind(issues);
        validateBoolean(issues, "cluster.event.outbox.jdbc.initialize.schema");
        validateBoolean(issues, "cluster.event.outbox.replay.enabled");
        validatePositiveInteger(issues, "cluster.event.outbox.replay.interval.millis");
        validatePositiveInteger(issues, "cluster.event.subscription.lease.ttl.millis");
        validatePositiveInteger(issues, "cluster.event.subscription.lease.renew.interval.millis");
        validatePositiveInteger(issues, "cluster.event.subscription.lease.scan.interval.millis");
        validateBoolean(issues, "cluster.event.repair.scheduler.enabled");
        validateBoolean(issues, "cluster.event.repair.dispatcher.enabled");
        validatePositiveInteger(issues, "cluster.event.repair.dispatcher.interval.millis");
        validatePositiveInteger(issues, "cluster.event.repair.dispatcher.max.drains.per.tick");
        validatePositiveInteger(issues, "cluster.event.repair.interval.millis");
        validatePositiveInteger(issues, "cluster.event.repair.max.batch.size");
        validateNonNegativeInteger(issues, "cluster.event.repair.priority");
        validateNonNegativeInteger(issues, "cluster.event.repair.backoff.initial.millis");
        validateNonNegativeInteger(issues, "cluster.event.repair.backoff.max.millis");
        validateDoubleAtLeast(issues, "cluster.event.repair.backoff.multiplier", 1.0);
        validateRepairBackoffBounds(issues,
                "cluster.event.repair.backoff.initial.millis",
                "cluster.event.repair.backoff.max.millis");
        validateNonNegativeInteger(issues, "cluster.event.repair.owner.isolation.max.failures");
        validateNonNegativeInteger(issues, "cluster.event.repair.owner.isolation.duration.millis");
        validateEventRepairTopicOverrides(issues);
        validateJdbcEventOutboxStore(issues);
        validateMigrationTaskStoreKind(issues);
        validateBoolean(issues, "cluster.migration.task.jdbc.initialize.schema");
        validateJdbcMigrationTaskStore(issues);
        validatePositiveInteger(issues, "cluster.config.warmup.timeout.millis");
        validatePositiveInteger(issues, "cluster.registry.lease.ttl.millis");
        validatePositiveInteger(issues, "cluster.registry.heartbeat.interval.millis");
        validatePositiveInteger(issues, "cluster.registry.lease.scan.interval.millis");
        validatePositiveInteger(issues, "cluster.registry.subscription.lease.ttl.millis");
        validatePositiveInteger(issues, "cluster.registry.subscription.lease.scan.interval.millis");
        validateNonNegativeInteger(issues, "cluster.registry.history.limit");
        validateBoolean(issues, "cluster.registry.recovery.enabled");
        validatePositiveInteger(issues, "cluster.registry.recovery.interval.millis");
        validateOptionalMetadataValue(issues, "cluster.route.tag");
        validateOptionalMetadataValue(issues, "cluster.deployment.group");
        validateServiceMetadataEntries(issues);
        validateBoolean(issues, "cluster.rpc.gray.enabled");
        validateOptionalMetadataValue(issues, "cluster.rpc.gray.stable.tag");
        validateOptionalMetadataValue(issues, "cluster.rpc.gray.gray.tag");
        validatePercent(issues, "cluster.rpc.gray.percent");
        validateLongCsv(issues, "cluster.rpc.gray.players");
        validateStringCsv(issues, "cluster.rpc.gray.operations");
        validateBoolean(issues, "cluster.migration.recovery.enabled");
        validatePositiveInteger(issues, "cluster.migration.recovery.scan.interval.millis");
        validatePositiveInteger(issues, "cluster.migration.recovery.lease.ttl.millis");
        validatePositiveInteger(issues, "cluster.migration.recovery.target.accept.attempts");
        validateBoolean(issues, "cluster.migration.task.retention.enabled");
        validatePositiveInteger(issues, "cluster.migration.task.retention.millis");
        validatePositiveInteger(issues, "cluster.migration.task.retention.scan.interval.millis");
        validateShopStockStoreKind(issues);
        validateBoolean(issues, "cluster.shop.stock.reservation.retention.enabled");
        validatePositiveInteger(issues, "cluster.shop.stock.reservation.ttl.millis");
        validatePositiveInteger(issues, "cluster.shop.stock.reservation.scan.interval.millis");
        ServiceKind actualKind = validateKind(issues, "cluster.kind");
        validatePositiveInteger(issues, "cluster.event.history.default.limit");
        validateEventHistoryTopicLimits(issues);
        if (actualKind != null && expectedKind != null && actualKind != expectedKind) {
            issues.add(new ClusterConfigIssue("cluster.kind", "expected " + expectedKind + " but was " + actualKind));
        }
        validateCenter(issues);
        if (actualKind == ServiceKind.SCENE || expectedKind == ServiceKind.SCENE) {
            validateScene(issues);
            validateBoolean(issues, "scene.tick.enabled");
            validateNonNegativeInteger(issues, "scene.tick.initial.delay.millis");
            validatePositiveInteger(issues, "scene.tick.interval.millis");
        }
        if (actualKind == ServiceKind.CHAT || expectedKind == ServiceKind.CHAT) {
            validatePositiveInteger(issues, "chat.world.shards");
            validatePositiveInteger(issues, "chat.history.max.messages");
            validatePositiveInteger(issues, "chat.delivery.max.pending.per.recipient");
            validateChatDeliveryOverflowStrategy(issues);
        }
        return new ClusterConfigValidation(issues);
    }

    public ServiceKind kind() {
        return ServiceKind.valueOf(required("cluster.kind"));
    }

    public String region() {
        return required("cluster.region");
    }

    public String node() {
        return required("cluster.node");
    }

    public ServiceId serviceId() {
        return ServiceId.of(kind(), region(), node());
    }

    public ServiceEndpoint endpoint() {
        return new ServiceEndpoint(required("cluster.host"), integer("cluster.port", 0));
    }

    public Map<String, String> serviceMetadata() {
        Map<String, String> metadata = new HashMap<>();
        String prefix = "cluster.metadata.";
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String metadataKey = key.substring(prefix.length());
                String value = properties.getProperty(key);
                if (!metadataKey.isBlank() && value != null && !value.isBlank()) {
                    metadata.put(metadataKey, value);
                }
            }
        }
        putIfPresent(metadata, ServiceMetadata.ROUTE_TAG, "cluster.route.tag");
        putIfPresent(metadata, ServiceMetadata.DEPLOYMENT_GROUP, "cluster.deployment.group");
        return Map.copyOf(metadata);
    }

    public PlayerGrayRouteConfig playerGrayRouteConfig() {
        return new PlayerGrayRouteConfig(
                Boolean.parseBoolean(property("cluster.rpc.gray.enabled", "false")),
                property("cluster.rpc.gray.stable.tag", "stable"),
                property("cluster.rpc.gray.gray.tag", "gray"),
                integer("cluster.rpc.gray.percent", 0),
                longSet("cluster.rpc.gray.players"),
                stringSet("cluster.rpc.gray.operations")
        );
    }

    public ServiceEndpoint centerEndpoint() {
        return new ServiceEndpoint(required("cluster.center.host"), integer("cluster.center.port", 0));
    }

    public ServiceEndpoint opsEndpoint() {
        return new ServiceEndpoint(property("cluster.ops.host", "127.0.0.1"), integer("cluster.ops.port", endpoint().port() + 10_000));
    }

    public boolean clientGatewayEnabled() {
        return Boolean.parseBoolean(property("cluster.client.enabled", "false"));
    }

    public ServiceEndpoint clientGatewayEndpoint() {
        return new ServiceEndpoint(
                property("cluster.client.host", endpoint().host()),
                integer("cluster.client.port", endpoint().port() + 20_000)
        );
    }

    public PlayerGatewayConfig playerGatewayConfig() {
        return new PlayerGatewayConfig(
                Duration.ofMillis(integer("cluster.client.reader.idle.timeout.millis", 0)),
                Boolean.parseBoolean(property("cluster.client.heartbeat.ack.enabled", "true")),
                PlayerGatewayDuplicateLoginPolicy.valueOf(property(
                        "cluster.client.duplicate.login.policy",
                        PlayerGatewayDuplicateLoginPolicy.KICK_OLD.name()
                )),
                new AgentRateLimitPolicy(
                        integer("cluster.client.command.rate.capacity", 200),
                        integer("cluster.client.command.rate.refill.permits", 200),
                        Duration.ofMillis(integer("cluster.client.command.rate.refill.interval.millis", 1_000))
                ),
                new AgentRateLimitPolicy(
                        integer("cluster.client.heartbeat.rate.capacity", 60),
                        integer("cluster.client.heartbeat.rate.refill.permits", 60),
                        Duration.ofMillis(integer("cluster.client.heartbeat.rate.refill.interval.millis", 1_000))
                ),
                integer("cluster.client.outbound.pending.ack.max.messages", 512),
                Duration.ofMillis(integer("cluster.client.outbound.pending.ack.max.age.millis", 30_000)),
                Boolean.parseBoolean(property("cluster.client.outbound.slow.close.enabled", "true"))
        );
    }

    public ServiceId centerServiceId() {
        return ServiceId.of(ServiceKind.CENTER, property("cluster.center.region", region()), property("cluster.center.node", "center-1"));
    }

    public int actorWorkers() {
        return integer("cluster.actor.workers", Runtime.getRuntime().availableProcessors());
    }

    public ActorSystemConfig actorSystemConfig() {
        ActorSystemConfig config = new ActorSystemConfig(
                actorWorkers(),
                integer("cluster.actor.batch.size", ActorSystemConfig.DEFAULT_BATCH_SIZE),
                integer("cluster.actor.mailbox.capacity", ActorSystemConfig.DEFAULT_MAILBOX_CAPACITY),
                ActorOverflowStrategy.valueOf(property("cluster.actor.overflow.strategy", ActorOverflowStrategy.REJECT.name())),
                Duration.ofMillis(integer("cluster.actor.shutdown.timeout.millis", 3_000))
        ).withSlowTaskThreshold(
                Duration.ofMillis(integer("cluster.actor.slow.task.threshold.millis", 0))
        );
        String prefix = "cluster.actor.category.";
        String suffix = ".capacity";
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix) && key.endsWith(suffix)) {
                String category = key.substring(prefix.length(), key.length() - suffix.length());
                config = config.withCategoryCapacity(ActorTaskCategory.valueOf(category), integer(key, 0));
            }
        }
        return config;
    }

    public RuntimeHealthPolicy runtimeHealthPolicy() {
        return new RuntimeHealthPolicy(
                integer("cluster.health.max.queued.tasks", 10_000),
                integer("cluster.health.max.pending.outbox.events", 0),
                integer("cluster.health.max.migration.pending.age.millis", 300_000),
                integer("cluster.health.max.scene.active.scenes", 0),
                integer("cluster.health.max.scene.active.players", 0),
                integer("cluster.health.max.scene.shard.hotspot.players", 0),
                integer("cluster.health.max.player.business.pending.responses", 0),
                integer("cluster.health.max.player.business.pending.age.millis", 0)
        );
    }

    public DrainConfig drainConfig() {
        return new DrainConfig(
                Duration.ofMillis(integer("cluster.drain.timeout.millis", 10_000)),
                Duration.ofMillis(integer("cluster.drain.poll.interval.millis", 50)),
                Duration.ofMillis(integer("cluster.drain.propagation.delay.millis", 200))
        );
    }

    public AgentRateLimitPolicy playerCommandRateLimitPolicy() {
        return new AgentRateLimitPolicy(
                integer("cluster.player.command.rate.capacity", 500),
                integer("cluster.player.command.rate.refill.permits", 500),
                Duration.ofMillis(integer("cluster.player.command.rate.refill.interval.millis", 1_000))
        );
    }

    public ActorMailboxPressurePolicy playerCommandMailboxPressurePolicy() {
        return mailboxPressurePolicy("cluster.player.command.mailbox.pressure");
    }

    public ActorMailboxPressurePolicy businessAgentMailboxPressurePolicy() {
        return mailboxPressurePolicy("cluster.business.agent.mailbox.pressure");
    }

    public ActorMailboxPressurePolicy chatMailboxPressurePolicy() {
        return mailboxPressurePolicy("cluster.chat.mailbox.pressure");
    }

    public ActorMailboxPressurePolicy sceneMailboxPressurePolicy() {
        return mailboxPressurePolicy("cluster.scene.mailbox.pressure");
    }

    private ActorMailboxPressurePolicy mailboxPressurePolicy(String prefix) {
        return new ActorMailboxPressurePolicy(
                Boolean.parseBoolean(property(prefix + ".enabled", "false")),
                integer(prefix + ".target.max.queued", 0),
                integer(prefix + ".group.max.queued", 0),
                Duration.ofMillis(integer(prefix + ".retry.after.millis", 50))
        );
    }

    public Duration playerBusinessResponseTimeout() {
        return Duration.ofMillis(integer("cluster.player.business.response.timeout.millis", 5_000));
    }

    public Instant gameServerOpenTime() {
        return Instant.parse(property("game.server.open.time", "1970-01-01T00:00:00Z"));
    }

    public PlayerStateStoreKind playerStateStoreKind() {
        return PlayerStateStoreKind.valueOf(property("cluster.player.state.store", PlayerStateStoreKind.MEMORY.name()));
    }

    public Path playerStateStoreDirectory() {
        return Path.of(property("cluster.player.state.store.dir", "data/player-state/" + region() + "-" + node()));
    }

    public boolean playerAutoSaveEnabled() {
        return Boolean.parseBoolean(property("cluster.player.auto.save.enabled", "true"));
    }

    public Duration playerAutoSaveInitialDelay() {
        return Duration.ofMillis(integer("cluster.player.auto.save.initial.delay.millis", 30_000));
    }

    public Duration playerAutoSaveInterval() {
        return Duration.ofMillis(integer("cluster.player.auto.save.interval.millis", 60_000));
    }

    public boolean playerGrowthStaminaRecoveryEnabled() {
        return Boolean.parseBoolean(property("cluster.player.growth.stamina.recovery.enabled", "false"));
    }

    public Duration playerGrowthStaminaRecoveryInitialDelay() {
        return Duration.ofMillis(integer("cluster.player.growth.stamina.recovery.initial.delay.millis", 5_000));
    }

    public Duration playerGrowthStaminaRecoveryInterval() {
        return Duration.ofMillis(integer("cluster.player.growth.stamina.recovery.interval.millis", 60_000));
    }

    public EventOutboxStoreKind eventOutboxStoreKind() {
        return EventOutboxStoreKind.valueOf(property("cluster.event.outbox.store", EventOutboxStoreKind.MEMORY.name()));
    }

    public String eventOutboxJdbcDriver() {
        return property("cluster.event.outbox.jdbc.driver", "");
    }

    public String eventOutboxJdbcUrl() {
        return required("cluster.event.outbox.jdbc.url");
    }

    public String eventOutboxJdbcUser() {
        return property("cluster.event.outbox.jdbc.user", "");
    }

    public String eventOutboxJdbcPassword() {
        return property("cluster.event.outbox.jdbc.password", "");
    }

    public String eventOutboxJdbcTable() {
        return property("cluster.event.outbox.jdbc.table", "versioned_event_outbox");
    }

    public String eventOutboxJdbcSequenceTable() {
        return property("cluster.event.outbox.jdbc.sequence.table", "versioned_event_outbox_sequence");
    }

    public boolean eventOutboxJdbcInitializeSchema() {
        return Boolean.parseBoolean(property("cluster.event.outbox.jdbc.initialize.schema", "true"));
    }

    public boolean eventOutboxReplayEnabled() {
        return Boolean.parseBoolean(property("cluster.event.outbox.replay.enabled", "true"));
    }

    public Duration eventOutboxReplayInterval() {
        return Duration.ofMillis(integer("cluster.event.outbox.replay.interval.millis", 5_000));
    }

    public Duration eventSubscriptionLeaseTtl() {
        return Duration.ofMillis(integer("cluster.event.subscription.lease.ttl.millis", 15_000));
    }

    public Duration eventSubscriptionLeaseRenewInterval() {
        return Duration.ofMillis(integer("cluster.event.subscription.lease.renew.interval.millis", 5_000));
    }

    public Duration eventSubscriptionRecoveryInterval() {
        return Duration.ofMillis(integer("cluster.event.subscription.recovery.interval.millis", 5_000));
    }

    public Duration eventSubscriptionLeaseScanInterval() {
        return Duration.ofMillis(integer("cluster.event.subscription.lease.scan.interval.millis", 1_000));
    }

    public boolean eventRepairSchedulerEnabled() {
        return Boolean.parseBoolean(property("cluster.event.repair.scheduler.enabled", "true"));
    }

    public boolean eventRepairSchedulerEnabled(String topic) {
        return Boolean.parseBoolean(property(
                eventRepairTopicKey(topic, "scheduler.enabled"),
                property("cluster.event.repair.scheduler.enabled", "true")
        ));
    }

    public boolean eventRepairDispatcherEnabled() {
        return Boolean.parseBoolean(property("cluster.event.repair.dispatcher.enabled", "true"));
    }

    public Duration eventRepairDispatcherInterval() {
        return Duration.ofMillis(integer("cluster.event.repair.dispatcher.interval.millis", 1_000));
    }

    public int eventRepairDispatcherMaxDrainsPerTick() {
        return integer("cluster.event.repair.dispatcher.max.drains.per.tick", 4);
    }

    public Duration eventRepairInterval() {
        return Duration.ofMillis(integer("cluster.event.repair.interval.millis", 5_000));
    }

    public Duration eventRepairInterval(String topic) {
        return Duration.ofMillis(integer(
                eventRepairTopicKey(topic, "interval.millis"),
                integer("cluster.event.repair.interval.millis", 5_000)
        ));
    }

    public int eventRepairMaxBatchSize() {
        return integer("cluster.event.repair.max.batch.size", DEFAULT_EVENT_REPAIR_MAX_BATCH_SIZE);
    }

    public int eventRepairMaxBatchSize(String topic) {
        return integer(
                eventRepairTopicKey(topic, "max.batch.size"),
                integer("cluster.event.repair.max.batch.size", DEFAULT_EVENT_REPAIR_MAX_BATCH_SIZE)
        );
    }

    public int eventRepairPriority() {
        return integer("cluster.event.repair.priority", 0);
    }

    public int eventRepairPriority(String topic) {
        return integer(
                eventRepairTopicKey(topic, "priority"),
                integer("cluster.event.repair.priority", 0)
        );
    }

    public OwnerEventRepairBackoffPolicy eventRepairBackoffPolicy(String topic) {
        Duration initialDelay = Duration.ofMillis(integer(
                eventRepairTopicKey(topic, "backoff.initial.millis"),
                integer("cluster.event.repair.backoff.initial.millis", 1_000)
        ));
        Duration maxDelay = Duration.ofMillis(integer(
                eventRepairTopicKey(topic, "backoff.max.millis"),
                integer("cluster.event.repair.backoff.max.millis", 30_000)
        ));
        double multiplier = doubleValue(
                eventRepairTopicKey(topic, "backoff.multiplier"),
                doubleValue("cluster.event.repair.backoff.multiplier", 2.0)
        );
        if (initialDelay.isZero() || maxDelay.isZero()) {
            return OwnerEventRepairBackoffPolicy.disabled();
        }
        return new OwnerEventRepairBackoffPolicy(initialDelay, maxDelay, multiplier);
    }

    public OwnerEventRepairIsolationPolicy eventRepairIsolationPolicy(String topic) {
        int maxFailures = integer(
                eventRepairTopicKey(topic, "owner.isolation.max.failures"),
                integer("cluster.event.repair.owner.isolation.max.failures", 3)
        );
        Duration duration = Duration.ofMillis(integer(
                eventRepairTopicKey(topic, "owner.isolation.duration.millis"),
                integer("cluster.event.repair.owner.isolation.duration.millis", 60_000)
        ));
        return new OwnerEventRepairIsolationPolicy(maxFailures, duration);
    }

    public Duration configWarmupTimeout() {
        return Duration.ofMillis(integer("cluster.config.warmup.timeout.millis", 5_000));
    }

    public Duration registryLeaseTtl() {
        return Duration.ofMillis(integer("cluster.registry.lease.ttl.millis", 15_000));
    }

    public Duration registryHeartbeatInterval() {
        return Duration.ofMillis(integer("cluster.registry.heartbeat.interval.millis", 5_000));
    }

    public Duration registryLeaseScanInterval() {
        return Duration.ofMillis(integer("cluster.registry.lease.scan.interval.millis", 1_000));
    }

    public Duration registrySubscriptionLeaseTtl() {
        return Duration.ofMillis(integer("cluster.registry.subscription.lease.ttl.millis", 15_000));
    }

    public Duration registrySubscriptionLeaseScanInterval() {
        return Duration.ofMillis(integer("cluster.registry.subscription.lease.scan.interval.millis", 1_000));
    }

    public int registryHistoryLimit() {
        return integer("cluster.registry.history.limit", com.commonbattle.cluster.InMemoryServiceRegistry.DEFAULT_HISTORY_LIMIT);
    }

    public boolean registryRecoveryEnabled() {
        return Boolean.parseBoolean(property("cluster.registry.recovery.enabled", "true"));
    }

    public Duration registryRecoveryInterval() {
        return Duration.ofMillis(integer("cluster.registry.recovery.interval.millis", 5_000));
    }

    public boolean migrationTaskRetentionEnabled() {
        return Boolean.parseBoolean(property("cluster.migration.task.retention.enabled", "true"));
    }

    public AgentMigrationTaskStoreKind migrationTaskStoreKind() {
        return AgentMigrationTaskStoreKind.valueOf(property("cluster.migration.task.store",
                AgentMigrationTaskStoreKind.MEMORY.name()));
    }

    public Path migrationTaskStoreDirectory() {
        return Path.of(property("cluster.migration.task.store.dir",
                "data/migration-tasks/" + region() + "-" + node()));
    }

    public String migrationTaskJdbcDriver() {
        return property("cluster.migration.task.jdbc.driver", "");
    }

    public String migrationTaskJdbcUrl() {
        return required("cluster.migration.task.jdbc.url");
    }

    public String migrationTaskJdbcUser() {
        return property("cluster.migration.task.jdbc.user", "");
    }

    public String migrationTaskJdbcPassword() {
        return property("cluster.migration.task.jdbc.password", "");
    }

    public String migrationTaskJdbcTable() {
        return property("cluster.migration.task.jdbc.table", "agent_migration_tasks");
    }

    public boolean migrationTaskJdbcInitializeSchema() {
        return Boolean.parseBoolean(property("cluster.migration.task.jdbc.initialize.schema", "true"));
    }

    public boolean migrationRecoveryEnabled() {
        return Boolean.parseBoolean(property("cluster.migration.recovery.enabled", "true"));
    }

    public Duration migrationRecoveryScanInterval() {
        return Duration.ofMillis(integer("cluster.migration.recovery.scan.interval.millis", 5_000));
    }

    public Duration migrationRecoveryLeaseTtl() {
        return Duration.ofMillis(integer("cluster.migration.recovery.lease.ttl.millis", 30_000));
    }

    public com.commonbattle.actor.agent.migration.AgentMigrationPolicy migrationPolicy() {
        return new com.commonbattle.actor.agent.migration.AgentMigrationPolicy(
                integer("cluster.migration.recovery.target.accept.attempts", 1)
        );
    }

    public Duration migrationTaskRetention() {
        return Duration.ofMillis(integer("cluster.migration.task.retention.millis", 86_400_000));
    }

    public Duration migrationTaskRetentionScanInterval() {
        return Duration.ofMillis(integer("cluster.migration.task.retention.scan.interval.millis", 60_000));
    }

    public boolean shopStockReservationRetentionEnabled() {
        return Boolean.parseBoolean(property("cluster.shop.stock.reservation.retention.enabled", "true"));
    }

    public ShopStockStoreKind shopStockStoreKind() {
        return ShopStockStoreKind.valueOf(property("cluster.shop.stock.store", ShopStockStoreKind.MEMORY.name()));
    }

    public Duration shopStockReservationTtl() {
        return Duration.ofMillis(integer("cluster.shop.stock.reservation.ttl.millis", 60_000));
    }

    public Duration shopStockReservationScanInterval() {
        return Duration.ofMillis(integer("cluster.shop.stock.reservation.scan.interval.millis", 5_000));
    }

    public ClusterEventHistoryPolicy eventHistoryPolicy() {
        String prefix = "cluster.event.history.topic.";
        String suffix = ".limit";
        Map<String, Integer> topicLimits = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix) && key.endsWith(suffix)) {
                String topic = key.substring(prefix.length(), key.length() - suffix.length());
                topicLimits.put(topic, integer(key, 0));
            }
        }
        return new ClusterEventHistoryPolicy(integer("cluster.event.history.default.limit", 10_000), topicLimits);
    }

    public SceneHostingMode sceneMode() {
        return SceneHostingMode.valueOf(property("scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name()));
    }

    public int sceneCapacity() {
        return integer("scene.capacity", 200);
    }

    public String sceneId() {
        return property("scene.id", "world-1");
    }

    public int sceneShards() {
        return integer("scene.shards", actorWorkers());
    }

    public boolean sceneTickEnabled() {
        return Boolean.parseBoolean(property("scene.tick.enabled", "false"));
    }

    public Duration sceneTickInitialDelay() {
        return Duration.ofMillis(integer("scene.tick.initial.delay.millis", 1_000));
    }

    public Duration sceneTickInterval() {
        return Duration.ofMillis(integer("scene.tick.interval.millis", 100));
    }

    public ChatRouteConfig chatRouteConfig() {
        ChatRouteConfig defaults = ChatRouteConfig.defaults();
        return new ChatRouteConfig(
                integer("chat.world.shards", defaults.worldShardCount()),
                integer("chat.history.max.messages", defaults.maxHistoryMessages()),
                integer("chat.delivery.max.pending.per.recipient", defaults.maxPendingDeliveriesPerRecipient()),
                com.commonbattle.game.chat.ChatDeliveryOverflowStrategy.valueOf(property(
                        "chat.delivery.overflow.strategy",
                        defaults.deliveryOverflowStrategy().name()
                ))
        );
    }

    private String required(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing config key " + key);
        }
        return value;
    }

    private String property(String key, String defaultValue) {
        return Objects.requireNonNullElse(properties.getProperty(key), defaultValue);
    }

    private int integer(String key, int defaultValue) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private double doubleValue(String key, double defaultValue) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : Double.parseDouble(value);
    }

    private void require(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            issues.add(new ClusterConfigIssue(key, "required"));
        }
    }

    private ServiceKind validateKind(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ServiceKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterConfigIssue(key, "unknown service kind " + value));
            return null;
        }
    }

    private void validateCenter(List<ClusterConfigIssue> issues) {
        require(issues, "cluster.center.host");
        validatePort(issues, "cluster.center.port");
    }

    private void validateScene(List<ClusterConfigIssue> issues) {
        String mode = properties.getProperty("scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name());
        try {
            SceneHostingMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterConfigIssue("scene.mode", "unknown scene mode " + mode));
        }
        validatePositiveInteger(issues, "scene.capacity");
        validatePositiveInteger(issues, "scene.shards");
    }

    private void validatePort(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            issues.add(new ClusterConfigIssue(key, "required"));
            return;
        }
        try {
            int port = Integer.parseInt(value);
            if (port <= 0 || port > 65_535) {
                issues.add(new ClusterConfigIssue(key, "must be between 1 and 65535"));
            }
        } catch (NumberFormatException e) {
            issues.add(new ClusterConfigIssue(key, "must be an integer"));
        }
    }

    private void validateOptionalPort(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return;
        }
        validatePort(issues, key);
    }

    private void validatePositiveInteger(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int number = Integer.parseInt(value);
            if (number <= 0) {
                issues.add(new ClusterConfigIssue(key, "must be positive"));
            }
        } catch (NumberFormatException e) {
            issues.add(new ClusterConfigIssue(key, "must be an integer"));
        }
    }

    private void validateInstant(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            Instant.parse(value);
        } catch (RuntimeException e) {
            issues.add(new ClusterConfigIssue(key, "must be ISO-8601 instant"));
        }
    }

    private void validateNonNegativeInteger(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int number = Integer.parseInt(value);
            if (number < 0) {
                issues.add(new ClusterConfigIssue(key, "must not be negative"));
            }
        } catch (NumberFormatException e) {
            issues.add(new ClusterConfigIssue(key, "must be an integer"));
        }
    }

    private void validateDoubleAtLeast(List<ClusterConfigIssue> issues, String key, double minValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            double number = Double.parseDouble(value);
            if (Double.isNaN(number) || Double.isInfinite(number) || number < minValue) {
                issues.add(new ClusterConfigIssue(key, "must be greater than or equal to " + minValue));
            }
        } catch (NumberFormatException e) {
            issues.add(new ClusterConfigIssue(key, "must be a number"));
        }
    }

    private void validateRepairBackoffBounds(List<ClusterConfigIssue> issues, String initialKey, String maxKey) {
        String initialValue = properties.getProperty(initialKey);
        String maxValue = properties.getProperty(maxKey);
        if (initialValue == null || initialValue.isBlank() || maxValue == null || maxValue.isBlank()) {
            return;
        }
        try {
            int initialMillis = Integer.parseInt(initialValue);
            int maxMillis = Integer.parseInt(maxValue);
            if (initialMillis > 0 && maxMillis > 0 && maxMillis < initialMillis) {
                issues.add(new ClusterConfigIssue(maxKey, "must be greater than or equal to initial backoff"));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void validatePercent(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int percent = Integer.parseInt(value);
            if (percent < 0 || percent > 100) {
                issues.add(new ClusterConfigIssue(key, "must be between 0 and 100"));
            }
        } catch (NumberFormatException e) {
            issues.add(new ClusterConfigIssue(key, "must be an integer"));
        }
    }

    private void validateLongCsv(List<ClusterConfigIssue> issues, String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String value : raw.split(",", -1)) {
            String trimmed = value.trim();
            if (trimmed.isBlank()) {
                issues.add(new ClusterConfigIssue(key, "must not contain blank item"));
                return;
            }
            try {
                Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                issues.add(new ClusterConfigIssue(key, "must contain only long integers"));
                return;
            }
        }
    }

    private void validateStringCsv(List<ClusterConfigIssue> issues, String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String value : raw.split(",", -1)) {
            if (value.trim().isBlank()) {
                issues.add(new ClusterConfigIssue(key, "must not contain blank item"));
                return;
            }
        }
    }

    private void validateBoolean(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return;
        }
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            issues.add(new ClusterConfigIssue(key, "must be true or false"));
        }
    }

    private void validateOptionalMetadataValue(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value != null && value.isBlank()) {
            issues.add(new ClusterConfigIssue(key, "must not be blank"));
        }
    }

    private void validateServiceMetadataEntries(List<ClusterConfigIssue> issues) {
        String prefix = "cluster.metadata.";
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String metadataKey = key.substring(prefix.length());
                String value = properties.getProperty(key);
                if (metadataKey.isBlank()) {
                    issues.add(new ClusterConfigIssue(key, "metadata key must not be blank"));
                }
                if (value == null || value.isBlank()) {
                    issues.add(new ClusterConfigIssue(key, "metadata value must not be blank"));
                }
            }
        }
        validateMetadataAliasConflict(issues, "cluster.route.tag", ServiceMetadata.ROUTE_TAG);
        validateMetadataAliasConflict(issues, "cluster.deployment.group", ServiceMetadata.DEPLOYMENT_GROUP);
    }

    private void validateMetadataAliasConflict(List<ClusterConfigIssue> issues, String aliasKey, String metadataKey) {
        String alias = properties.getProperty(aliasKey);
        String generic = properties.getProperty("cluster.metadata." + metadataKey);
        if (alias != null && !alias.isBlank()
                && generic != null && !generic.isBlank()
                && !alias.equals(generic)) {
            issues.add(new ClusterConfigIssue(aliasKey, "conflicts with cluster.metadata." + metadataKey));
        }
    }

    private void validateActorOverflowStrategy(List<ClusterConfigIssue> issues) {
        String value = properties.getProperty("cluster.actor.overflow.strategy");
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            ActorOverflowStrategy.valueOf(value);
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterConfigIssue("cluster.actor.overflow.strategy", "unknown overflow strategy " + value));
        }
    }

    private void validateActorCategoryCapacities(List<ClusterConfigIssue> issues) {
        String prefix = "cluster.actor.category.";
        String suffix = ".capacity";
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix) && key.endsWith(suffix)) {
                String category = key.substring(prefix.length(), key.length() - suffix.length());
                try {
                    ActorTaskCategory.valueOf(category);
                } catch (IllegalArgumentException e) {
                    issues.add(new ClusterConfigIssue(key, "unknown actor task category " + category));
                }
                validatePositiveInteger(issues, key);
            }
        }
    }

    private void validateMigrationTaskStoreKind(List<ClusterConfigIssue> issues) {
        String value = properties.getProperty("cluster.migration.task.store");
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            AgentMigrationTaskStoreKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterConfigIssue("cluster.migration.task.store", "unknown migration task store " + value));
        }
    }

    private void validateEventOutboxStoreKind(List<ClusterConfigIssue> issues) {
        String value = properties.getProperty("cluster.event.outbox.store");
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            EventOutboxStoreKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterConfigIssue("cluster.event.outbox.store", "unknown event outbox store " + value));
        }
    }

    private void validatePlayerStateStoreKind(List<ClusterConfigIssue> issues) {
        String value = properties.getProperty("cluster.player.state.store");
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            PlayerStateStoreKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterConfigIssue("cluster.player.state.store", "unknown player state store " + value));
        }
    }

    private void validateJdbcEventOutboxStore(List<ClusterConfigIssue> issues) {
        String value = properties.getProperty("cluster.event.outbox.store");
        if (value == null || value.isBlank() || !value.equals(EventOutboxStoreKind.JDBC.name())) {
            return;
        }
        require(issues, "cluster.event.outbox.jdbc.url");
        validateTableName(issues, "cluster.event.outbox.jdbc.table");
        validateTableName(issues, "cluster.event.outbox.jdbc.sequence.table");
    }

    private void validateJdbcMigrationTaskStore(List<ClusterConfigIssue> issues) {
        String value = properties.getProperty("cluster.migration.task.store");
        if (value == null || value.isBlank() || !value.equals(AgentMigrationTaskStoreKind.JDBC.name())) {
            return;
        }
        require(issues, "cluster.migration.task.jdbc.url");
        String table = properties.getProperty("cluster.migration.task.jdbc.table");
        if (table != null && !table.matches("[A-Za-z][A-Za-z0-9_]*")) {
            issues.add(new ClusterConfigIssue("cluster.migration.task.jdbc.table", "invalid table name"));
        }
    }

    private void validateTableName(List<ClusterConfigIssue> issues, String key) {
        String table = properties.getProperty(key);
        if (table != null && !table.matches("[A-Za-z][A-Za-z0-9_]*")) {
            issues.add(new ClusterConfigIssue(key, "invalid table name"));
        }
    }

    private void validateShopStockStoreKind(List<ClusterConfigIssue> issues) {
        String value = properties.getProperty("cluster.shop.stock.store");
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            ShopStockStoreKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterConfigIssue("cluster.shop.stock.store", "unknown shop stock store " + value));
        }
    }

    private void validateChatDeliveryOverflowStrategy(List<ClusterConfigIssue> issues) {
        String value = properties.getProperty("chat.delivery.overflow.strategy");
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            com.commonbattle.game.chat.ChatDeliveryOverflowStrategy.valueOf(value);
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterConfigIssue("chat.delivery.overflow.strategy", "unknown chat delivery overflow strategy " + value));
        }
    }

    private void validateDuplicateLoginPolicy(List<ClusterConfigIssue> issues) {
        String value = properties.getProperty("cluster.client.duplicate.login.policy");
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            PlayerGatewayDuplicateLoginPolicy.valueOf(value);
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterConfigIssue("cluster.client.duplicate.login.policy",
                    "unknown duplicate login policy " + value));
        }
    }

    private void validateEventHistoryTopicLimits(List<ClusterConfigIssue> issues) {
        String prefix = "cluster.event.history.topic.";
        String suffix = ".limit";
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix) && key.endsWith(suffix)) {
                String topic = key.substring(prefix.length(), key.length() - suffix.length());
                if (topic.isBlank()) {
                    issues.add(new ClusterConfigIssue(key, "topic must not be blank"));
                }
                validatePositiveInteger(issues, key);
            }
        }
    }

    private void validateEventRepairTopicOverrides(List<ClusterConfigIssue> issues) {
        String prefix = "cluster.event.repair.topic.";
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            if (validTopicOverrideKey(key, prefix, ".scheduler.enabled")) {
                validateBoolean(issues, key);
            } else if (validTopicOverrideKey(key, prefix, ".interval.millis")
                    || validTopicOverrideKey(key, prefix, ".max.batch.size")) {
                validatePositiveInteger(issues, key);
            } else if (validTopicOverrideKey(key, prefix, ".priority")) {
                validateNonNegativeInteger(issues, key);
            } else if (validTopicOverrideKey(key, prefix, ".backoff.initial.millis")
                    || validTopicOverrideKey(key, prefix, ".backoff.max.millis")) {
                validateNonNegativeInteger(issues, key);
            } else if (validTopicOverrideKey(key, prefix, ".backoff.multiplier")) {
                validateDoubleAtLeast(issues, key, 1.0);
            } else if (validTopicOverrideKey(key, prefix, ".owner.isolation.max.failures")
                    || validTopicOverrideKey(key, prefix, ".owner.isolation.duration.millis")) {
                validateNonNegativeInteger(issues, key);
            } else {
                issues.add(new ClusterConfigIssue(key, "unknown event repair topic override"));
            }
        }
        for (String topic : eventRepairBackoffOverrideTopics(prefix)) {
            validateRepairBackoffBounds(issues,
                    eventRepairTopicKey(topic, "backoff.initial.millis"),
                    eventRepairTopicKey(topic, "backoff.max.millis"));
        }
    }

    private Set<String> eventRepairBackoffOverrideTopics(String prefix) {
        Set<String> topics = new HashSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (validTopicOverrideKey(key, prefix, ".backoff.initial.millis")) {
                topics.add(key.substring(prefix.length(), key.length() - ".backoff.initial.millis".length()));
            } else if (validTopicOverrideKey(key, prefix, ".backoff.max.millis")) {
                topics.add(key.substring(prefix.length(), key.length() - ".backoff.max.millis".length()));
            }
        }
        return topics;
    }

    private static boolean validTopicOverrideKey(String key, String prefix, String suffix) {
        return key.endsWith(suffix) && key.length() > prefix.length() + suffix.length();
    }

    private static String eventRepairTopicKey(String topic, String suffix) {
        Objects.requireNonNull(topic, "topic");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        return "cluster.event.repair.topic." + topic + "." + suffix;
    }

    private void putIfPresent(Map<String, String> metadata, String metadataKey, String propertyKey) {
        String value = properties.getProperty(propertyKey);
        if (value != null && !value.isBlank()) {
            metadata.put(metadataKey, value);
        }
    }

    private Set<Long> longSet(String key) {
        Set<Long> values = new HashSet<>();
        for (String value : csv(key)) {
            values.add(Long.parseLong(value));
        }
        return values;
    }

    private Set<String> stringSet(String key) {
        return Set.copyOf(csv(key));
    }

    private List<String> csv(String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }
}
