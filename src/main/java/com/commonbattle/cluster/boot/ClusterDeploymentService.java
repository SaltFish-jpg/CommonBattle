package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ServiceKind;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 集群部署清单中的单个进程定义。
 * 运维或测试可据此找到默认配置、Java 启动类，以及该进程启动后订阅的服务类型。
 */
public record ClusterDeploymentService(
        String name,
        String configResource,
        String mainClassName,
        ServiceKind kind,
        Set<ServiceKind> watches,
        Set<String> startsAfter
) {
    public ClusterDeploymentService {
        requireText(name, "name");
        requireText(configResource, "configResource");
        requireText(mainClassName, "mainClassName");
        Objects.requireNonNull(kind, "kind");
        watches = copyWatches(watches);
        startsAfter = copyStartsAfter(startsAfter);
    }

    private static Set<ServiceKind> copyWatches(Set<ServiceKind> watches) {
        Objects.requireNonNull(watches, "watches");
        EnumSet<ServiceKind> copy = EnumSet.noneOf(ServiceKind.class);
        copy.addAll(watches);
        return Set.copyOf(copy);
    }

    private static Set<String> copyStartsAfter(Set<String> startsAfter) {
        Objects.requireNonNull(startsAfter, "startsAfter");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String serviceName : startsAfter) {
            requireText(serviceName, "startsAfter service");
            copy.add(serviceName);
        }
        return Set.copyOf(copy);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
    }
}
