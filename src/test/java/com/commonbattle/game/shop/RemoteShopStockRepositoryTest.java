package com.commonbattle.game.shop;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteShopStockRepositoryTest {
    @Test
    void gameServiceReservesAndReleasesCenterStockThroughRpc() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterRpcGateway centerGateway = null;
        ClusterRpcGateway gameGateway = null;
        try {
            InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
            ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                    ShopStockOperations.RESERVE,
                    ShopStockOperations.RELEASE,
                    ShopStockOperations.REMAINING
            ));
            ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of());
            registry.register(center);
            registry.register(game);
            ClusterTopology topology = ClusterTopology.defaultCrossServer();
            centerGateway = new ClusterRpcGateway(center, watchedDirectory(registry), topology, transport);
            InMemoryShopStockRepository centerStocks = new InMemoryShopStockRepository();
            centerStocks.set("limited_pack", 1);
            new ShopStockEndpoint(centerStocks).bind(centerGateway);
            gameGateway = new ClusterRpcGateway(game, watchedDirectory(registry), topology, transport);
            RemoteShopStockRepository remoteStocks = new RemoteShopStockRepository(gameGateway, Duration.ofSeconds(1));

            assertEquals(1, remoteStocks.remaining("limited_pack"));
            assertTrue(remoteStocks.reserve("order-10001-1", "limited_pack", 1));
            assertTrue(remoteStocks.reserve("order-10001-1", "limited_pack", 1));
            assertEquals(0, remoteStocks.remaining("limited_pack"));
            assertFalse(remoteStocks.reserve("limited_pack", 1));
            remoteStocks.release("order-10001-1", "limited_pack", 1);
            remoteStocks.release("order-10001-1", "limited_pack", 1);

            assertEquals(1, remoteStocks.remaining("limited_pack"));
            assertEquals(1, centerStocks.remaining("limited_pack"));
        } finally {
            if (centerGateway != null) {
                centerGateway.close();
            }
            if (gameGateway != null) {
                gameGateway.close();
            }
            transport.close();
        }
    }

    private static ClusterDirectory watchedDirectory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> operations) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                operations,
                Map.of()
        );
    }
}
