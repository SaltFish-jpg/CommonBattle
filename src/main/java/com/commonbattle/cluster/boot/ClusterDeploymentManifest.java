package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ServiceKind;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * 集群部署清单。
 * 它描述一组可独立部署的服务进程，并校验清单与默认节点配置、启动类之间是否一致。
 */
public final class ClusterDeploymentManifest {
    public static final String DEFAULT_RESOURCE = "cluster/deployment.properties";

    private final List<ClusterDeploymentService> services;

    private ClusterDeploymentManifest(List<ClusterDeploymentService> services) {
        this.services = List.copyOf(services);
    }

    public static ClusterDeploymentManifest defaultManifest() {
        return fromClasspath(DEFAULT_RESOURCE);
    }

    public static ClusterDeploymentManifest fromClasspath(String resource) {
        try (InputStream input = ClusterDeploymentManifest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing classpath deployment manifest " + resource);
            }
            Properties properties = new Properties();
            properties.load(input);
            return fromProperties(properties);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load classpath deployment manifest " + resource, e);
        }
    }

    public static ClusterDeploymentManifest fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        List<String> names = csv(properties.getProperty("cluster.deployment.services"));
        List<ClusterDeploymentService> services = new ArrayList<>();
        for (String name : names) {
            String prefix = "cluster.deployment.service." + name + ".";
            services.add(new ClusterDeploymentService(
                    name,
                    required(properties, prefix + "config"),
                    required(properties, prefix + "main"),
                    ServiceKind.valueOf(required(properties, prefix + "kind")),
                    kinds(properties.getProperty(prefix + "watches")),
                    Set.copyOf(csvOrEmpty(properties.getProperty(prefix + "startsAfter")))
            ));
        }
        return new ClusterDeploymentManifest(services);
    }

    public List<ClusterDeploymentService> services() {
        return services;
    }

    public Optional<ClusterDeploymentService> service(String name) {
        Objects.requireNonNull(name, "name");
        return services.stream().filter(service -> service.name().equals(name)).findFirst();
    }

    public List<ClusterDeploymentService> startupOrder() {
        Map<String, ClusterDeploymentService> byName = new HashMap<>();
        for (ClusterDeploymentService service : services) {
            byName.put(service.name(), service);
        }
        List<ClusterDeploymentService> ordered = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (ClusterDeploymentService service : services) {
            visit(service, byName, visiting, visited, ordered);
        }
        return List.copyOf(ordered);
    }

    public ClusterDeploymentValidation validate() {
        List<ClusterDeploymentIssue> issues = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (ClusterDeploymentService service : services) {
            if (!names.add(service.name())) {
                issues.add(new ClusterDeploymentIssue(service.name(), "name", "duplicate service name"));
            }
            validateStartsAfter(service, issues);
            validateConfig(service, issues);
            validateMainClass(service, issues);
        }
        return new ClusterDeploymentValidation(issues);
    }

    private void validateStartsAfter(ClusterDeploymentService service, List<ClusterDeploymentIssue> issues) {
        Set<String> names = new LinkedHashSet<>(services.stream().map(ClusterDeploymentService::name).toList());
        if (service.startsAfter().contains(service.name())) {
            issues.add(new ClusterDeploymentIssue(service.name(), "startsAfter", "service cannot depend on itself"));
        }
        for (String dependency : service.startsAfter()) {
            if (!names.contains(dependency)) {
                issues.add(new ClusterDeploymentIssue(service.name(), "startsAfter", "unknown dependency " + dependency));
            }
        }
        try {
            startupOrder();
        } catch (IllegalArgumentException e) {
            issues.add(new ClusterDeploymentIssue(service.name(), "startsAfter", e.getMessage()));
        }
    }

    private static void validateConfig(ClusterDeploymentService service, List<ClusterDeploymentIssue> issues) {
        try {
            ClusterConfigValidation validation = ClusterNodeConfig.fromClasspath(service.configResource()).validate(service.kind());
            for (ClusterConfigIssue issue : validation.issues()) {
                issues.add(new ClusterDeploymentIssue(service.name(), issue.key(), issue.message()));
            }
        } catch (RuntimeException e) {
            issues.add(new ClusterDeploymentIssue(service.name(), "config", e.getMessage()));
        }
    }

    private static void validateMainClass(ClusterDeploymentService service, List<ClusterDeploymentIssue> issues) {
        try {
            Class<?> type = Class.forName(service.mainClassName());
            Method main = type.getMethod("main", String[].class);
            if (!Modifier.isStatic(main.getModifiers()) || !Modifier.isPublic(main.getModifiers())) {
                issues.add(new ClusterDeploymentIssue(service.name(), "main", "main method must be public static"));
            }
        } catch (ReflectiveOperationException e) {
            issues.add(new ClusterDeploymentIssue(service.name(), "main", e.getMessage()));
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing deployment key " + key);
        }
        return value.trim();
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing deployment key cluster.deployment.services");
        }
        List<String> values = new ArrayList<>();
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static Set<ServiceKind> kinds(String raw) {
        Set<ServiceKind> values = new LinkedHashSet<>();
        for (String value : csvOrEmpty(raw)) {
            values.add(ServiceKind.valueOf(value));
        }
        return values;
    }

    private static void visit(
            ClusterDeploymentService service,
            Map<String, ClusterDeploymentService> byName,
            Set<String> visiting,
            Set<String> visited,
            List<ClusterDeploymentService> ordered
    ) {
        if (visited.contains(service.name())) {
            return;
        }
        if (!visiting.add(service.name())) {
            throw new IllegalArgumentException("cycle detected at " + service.name());
        }
        for (String dependencyName : service.startsAfter()) {
            ClusterDeploymentService dependency = byName.get(dependencyName);
            if (dependency == null) {
                continue;
            }
            visit(dependency, byName, visiting, visited, ordered);
        }
        visiting.remove(service.name());
        visited.add(service.name());
        ordered.add(service);
    }

    private static List<String> csvOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
