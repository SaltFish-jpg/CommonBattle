package com.commonbattle.game.shop;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
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

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopStockEndpointActorTest {
    @Test
    void rpcHandlerOnlyEnqueuesAndStockChangesInsideSkuMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 1, 16);
        LocalClusterTransport transport = new LocalClusterTransport();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                ShopStockOperations.RESERVE
        ));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of());
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        registry.register(center);
        registry.register(game);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(
                center, directory(registry), ClusterTopology.defaultCrossServer(), transport
        );
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(
                game, directory(registry), ClusterTopology.defaultCrossServer(), transport
        );
        InMemoryShopStockRepository stocks = new InMemoryShopStockRepository();
        stocks.set("limited_pack", 1);
        new ShopStockEndpoint(stocks, actors, "center-shop-stock").bind(centerGateway);
        AtomicReference<ShopStockReserveResponse> response = new AtomicReference<>();

        gameGateway.call(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                ShopStockOperations.RESERVE,
                new ShopStockReserveRequest("order-1", "limited_pack", 1),
                ShopStockReserveResponse.class
        ), new RpcCallback<>() {
            @Override
            public void success(ShopStockReserveResponse value) {
                response.set(value);
            }

            @Override
            public void failure(Throwable error) {
                throw new AssertionError(error);
            }
        });

        assertEquals(1, stocks.remaining("limited_pack"));
        assertNull(response.get());
        assertEquals(1, executor.queued());

        executor.runNext();

        assertEquals(0, stocks.remaining("limited_pack"));
        assertTrue(response.get().reserved());
        centerGateway.close();
        gameGateway.close();
        actors.close();
        transport.close();
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
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

    private static final class RecordingExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int queued() {
            return tasks.size();
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }
}
