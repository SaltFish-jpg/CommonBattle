package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorOverflowStrategy;
import com.commonbattle.actor.ActorSystemConfig;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.backpressure.AgentRateLimitPolicy;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventHistoryPolicy;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * 独立部署进程使用的节点配置。
 * 配置可以来自外部 properties 文件，也可以来自 classpath 下的示例配置。
 */
public final class ClusterNodeConfig {
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
        validatePositiveInteger(issues, "cluster.actor.workers");
        validatePositiveInteger(issues, "cluster.actor.batch.size");
        validatePositiveInteger(issues, "cluster.actor.mailbox.capacity");
        validatePositiveInteger(issues, "cluster.actor.shutdown.timeout.millis");
        validatePositiveInteger(issues, "cluster.player.command.rate.capacity");
        validatePositiveInteger(issues, "cluster.player.command.rate.refill.permits");
        validatePositiveInteger(issues, "cluster.player.command.rate.refill.interval.millis");
        validateBoolean(issues, "cluster.player.auto.save.enabled");
        validateNonNegativeInteger(issues, "cluster.player.auto.save.initial.delay.millis");
        validatePositiveInteger(issues, "cluster.player.auto.save.interval.millis");
        validateInstant(issues, "game.server.open.time");
        validateActorOverflowStrategy(issues);
        validateActorCategoryCapacities(issues);
        validateNonNegativeInteger(issues, "cluster.health.max.queued.tasks");
        validateNonNegativeInteger(issues, "cluster.health.max.pending.outbox.events");
        validateNonNegativeInteger(issues, "cluster.health.max.migration.pending.age.millis");
        validateNonNegativeInteger(issues, "cluster.health.max.scene.active.scenes");
        validateNonNegativeInteger(issues, "cluster.health.max.scene.active.players");
        validateNonNegativeInteger(issues, "cluster.health.max.scene.shard.hotspot.players");
        validatePositiveInteger(issues, "cluster.drain.timeout.millis");
        validatePositiveInteger(issues, "cluster.drain.poll.interval.millis");
        validateNonNegativeInteger(issues, "cluster.drain.propagation.delay.millis");
        validateEventOutboxStoreKind(issues);
        validatePlayerStateStoreKind(issues);
        validateBoolean(issues, "cluster.event.outbox.jdbc.initialize.schema");
        validateBoolean(issues, "cluster.event.outbox.replay.enabled");
        validatePositiveInteger(issues, "cluster.event.outbox.replay.interval.millis");
        validateJdbcEventOutboxStore(issues);
        validateMigrationTaskStoreKind(issues);
        validateBoolean(issues, "cluster.migration.task.jdbc.initialize.schema");
        validateJdbcMigrationTaskStore(issues);
        validatePositiveInteger(issues, "cluster.config.warmup.timeout.millis");
        validatePositiveInteger(issues, "cluster.registry.lease.ttl.millis");
        validatePositiveInteger(issues, "cluster.registry.heartbeat.interval.millis");
        validatePositiveInteger(issues, "cluster.registry.lease.scan.interval.millis");
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
        validatePositiveInteger(issues, "cluster.event.history.default.limit");
        validateEventHistoryTopicLimits(issues);
        ServiceKind actualKind = validateKind(issues, "cluster.kind");
        if (actualKind != null && expectedKind != null && actualKind != expectedKind) {
            issues.add(new ClusterConfigIssue("cluster.kind", "expected " + expectedKind + " but was " + actualKind));
        }
        validateCenter(issues);
        if (actualKind == ServiceKind.SCENE || expectedKind == ServiceKind.SCENE) {
            validateScene(issues);
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

    public ServiceEndpoint centerEndpoint() {
        return new ServiceEndpoint(required("cluster.center.host"), integer("cluster.center.port", 0));
    }

    public ServiceEndpoint opsEndpoint() {
        return new ServiceEndpoint(property("cluster.ops.host", "127.0.0.1"), integer("cluster.ops.port", endpoint().port() + 10_000));
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
                integer("cluster.health.max.scene.shard.hotspot.players", 0)
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

    private void validateBoolean(List<ClusterConfigIssue> issues, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return;
        }
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            issues.add(new ClusterConfigIssue(key, "must be true or false"));
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
}
