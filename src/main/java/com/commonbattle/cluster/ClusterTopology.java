package com.commonbattle.cluster;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 跨服链接网络策略。
 * 直接链路存在时直连；没有直连时优先选择同 region 的 Proxy 服作为下一跳转发边界。
 */
public final class ClusterTopology {
    private final Set<ClusterLink> links = new HashSet<>();

    public ClusterTopology allow(ServiceKind from, ServiceKind to) {
        links.add(new ClusterLink(from, to));
        return this;
    }

    public boolean canConnect(ServiceKind from, ServiceKind to) {
        return links.contains(new ClusterLink(from, to));
    }

    public ServiceDescriptor nextHop(
            ServiceId source,
            ServiceDescriptor target,
            List<ServiceDescriptor> proxies
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(proxies, "proxies");
        if (canConnect(source.kind(), target.id().kind())) {
            return target;
        }
        Optional<ServiceDescriptor> proxy = proxies.stream()
                .filter(service -> service.id().kind() == ServiceKind.PROXY)
                .filter(service -> service.id().region().equals(source.region()))
                .findFirst();
        return proxy.orElseThrow(() -> new IllegalStateException(
                "No route from " + source.wireName() + " to " + target.id().wireName()
        ));
    }

    public static ClusterTopology defaultCrossServer() {
        return new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.CENTER)
                .allow(ServiceKind.GAME, ServiceKind.REGION)
                .allow(ServiceKind.GAME, ServiceKind.PROXY)
                .allow(ServiceKind.GAME, ServiceKind.CHAT)
                .allow(ServiceKind.CHAT, ServiceKind.CENTER)
                .allow(ServiceKind.CHAT, ServiceKind.REGION)
                .allow(ServiceKind.CHAT, ServiceKind.PROXY)
                .allow(ServiceKind.CHAT, ServiceKind.GAME)
                .allow(ServiceKind.CHAT, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.CENTER)
                .allow(ServiceKind.SCENE, ServiceKind.REGION)
                .allow(ServiceKind.SCENE, ServiceKind.PROXY)
                .allow(ServiceKind.SCENE, ServiceKind.CHAT)
                .allow(ServiceKind.REGION, ServiceKind.CENTER)
                .allow(ServiceKind.REGION, ServiceKind.GAME)
                .allow(ServiceKind.REGION, ServiceKind.CHAT)
                .allow(ServiceKind.REGION, ServiceKind.SCENE)
                .allow(ServiceKind.PROXY, ServiceKind.CENTER)
                .allow(ServiceKind.PROXY, ServiceKind.REGION)
                .allow(ServiceKind.PROXY, ServiceKind.GAME)
                .allow(ServiceKind.PROXY, ServiceKind.CHAT)
                .allow(ServiceKind.PROXY, ServiceKind.SCENE)
                .allow(ServiceKind.CENTER, ServiceKind.REGION)
                .allow(ServiceKind.CENTER, ServiceKind.GAME)
                .allow(ServiceKind.CENTER, ServiceKind.CHAT)
                .allow(ServiceKind.CENTER, ServiceKind.SCENE)
                .allow(ServiceKind.CENTER, ServiceKind.PROXY);
    }
}
