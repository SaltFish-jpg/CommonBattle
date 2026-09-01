package com.commonbattle.example.cross;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ForwardingProxy;
import com.commonbattle.cluster.network.LocalClusterTransport;

import java.util.Map;
import java.util.Set;

/**
 * 跨服服务注册订阅流程示例。
 * Region、Game、Scene、Center、Proxy 都先注册服务描述，再由各节点订阅自己关心的服务类型并构建本地路由。
 */
public final class CrossServerServiceBootstrap {
    private CrossServerServiceBootstrap() {
    }

    public static ClusterDirectory startRegionView(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        directory.watch(ServiceKind.GAME);
        directory.watch(ServiceKind.SCENE);
        directory.watch(ServiceKind.PROXY);
        return directory;
    }

    public static ServiceDescriptor descriptor(ServiceKind kind, String region, String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(kind, region, node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(kind.name().toLowerCase() + ".heartbeat"),
                Map.of()
        );
    }

    public static ClusterTopology topology() {
        return ClusterTopology.defaultCrossServer();
    }

    public static ForwardingProxy bindLocalProxy(LocalClusterTransport transport, ServiceDescriptor proxy) {
        ForwardingProxy forwarder = new ForwardingProxy(transport);
        forwarder.bind(proxy);
        return forwarder;
    }
}
