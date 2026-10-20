package com.commonbattle.cluster;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
        return nextHops(source, target, proxies).getFirst();
    }

    /**
     * 返回按负载和服务标识稳定排序的可用下一跳。
     * RPC 请求可在该列表上轮询，响应则可优先原路径并在失败后依次切换备用代理。
     */
    public List<ServiceDescriptor> nextHops(
            ServiceId source,
            ServiceDescriptor target,
            List<ServiceDescriptor> proxies
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(proxies, "proxies");
        if (canConnect(source.kind(), target.id().kind())) {
            return List.of(target);
        }
        List<ServiceDescriptor> candidates = proxies.stream()
                .filter(service -> service.id().kind() == ServiceKind.PROXY)
                .filter(service -> service.id().region().equals(source.region()))
                .sorted(Comparator.comparingLong(ServiceDescriptor::loadScore)
                        .thenComparing(service -> service.id().wireName()))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No route from " + source.wireName() + " to " + target.id().wireName()
            );
        }
        return candidates;
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
