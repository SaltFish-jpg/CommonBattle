package com.commonbattle.cluster;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterTopologyTest {
    @Test
    void gameRoutesSceneTrafficThroughProxyWhenNoDirectLinkExists() {
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        ServiceId game = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9100);
        ServiceDescriptor proxy = descriptor(ServiceKind.PROXY, "proxy-1", 9200);

        ServiceDescriptor nextHop = topology.nextHop(game, scene, java.util.List.of(proxy));

        assertEquals(proxy, nextHop);
    }

    @Test
    void regionCanRouteToSceneDirectly() {
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        ServiceId region = ServiceId.of(ServiceKind.REGION, "r1", "region-1");
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9100);

        ServiceDescriptor nextHop = topology.nextHop(region, scene, java.util.List.of());

        assertEquals(scene, nextHop);
    }

    @Test
    void exposesAllSameRegionProxiesInStableLoadOrder() {
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        ServiceId game = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9100);
        ServiceDescriptor otherRegion = new ServiceDescriptor(
                ServiceId.of(ServiceKind.PROXY, "r2", "proxy-other"),
                new ServiceEndpoint("127.0.0.1", 9200), Set.of(), Map.of()
        );
        ServiceDescriptor busy = ServiceMetadata.withLoad(
                descriptor(ServiceKind.PROXY, "proxy-busy", 9201), 20, 100
        );
        ServiceDescriptor idle = ServiceMetadata.withLoad(
                descriptor(ServiceKind.PROXY, "proxy-idle", 9202), 5, 100
        );

        assertEquals(
                java.util.List.of(idle, busy),
                topology.nextHops(game, scene, java.util.List.of(otherRegion, busy, idle))
        );
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(kind.name().toLowerCase() + ".heartbeat"),
                Map.of()
        );
    }
}
