package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventHistoryPolicy;
import com.commonbattle.example.cross.scene.SceneHostingMode;

import java.io.IOException;
import java.io.InputStream;
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
        validatePositiveInteger(issues, "cluster.config.warmup.timeout.millis");
        validatePositiveInteger(issues, "cluster.registry.lease.ttl.millis");
        validatePositiveInteger(issues, "cluster.registry.heartbeat.interval.millis");
        validatePositiveInteger(issues, "cluster.registry.lease.scan.interval.millis");
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
