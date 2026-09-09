package com.commonbattle.game.shop;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActorMailboxShopStockClientTest {
    @Test
    void reserveCallbackRunsOnlyAfterOwnerMailboxIsScheduled() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef owner = actors.actor("player-10001");
        RecordingAsyncClient async = new RecordingAsyncClient();
        ActorMailboxShopStockClient client = new ActorMailboxShopStockClient(
                async,
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                owner
        );
        AtomicReference<ShopStockReserveResponse> response = new AtomicReference<>();
        AtomicReference<String> actorId = new AtomicReference<>();

        client.reserve("order-10001-1", "limited_pack", 1, new ActorShopStockCallback<>() {
            @Override
            public void success(com.commonbattle.actor.ActorContext context, ShopStockReserveResponse result) {
                actorId.set(context.self().id());
                response.set(result);
            }

            @Override
            public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                throw new AssertionError(error);
            }
        });
        async.reserveCallback.success(new ShopStockReserveResponse(true, 0));

        assertNull(response.get());
        executor.runNext();

        assertEquals("player-10001", actorId.get());
        assertEquals(new ShopStockReserveResponse(true, 0), response.get());
    }

    @Test
    void failureCallbackAlsoRunsInsideOwnerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        RecordingAsyncClient async = new RecordingAsyncClient();
        ActorMailboxShopStockClient client = new ActorMailboxShopStockClient(
                async,
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("player-10001")
        );
        AtomicReference<Throwable> failure = new AtomicReference<>();

        client.reserve("order-10001-1", "limited_pack", 1, new ActorShopStockCallback<>() {
            @Override
            public void success(com.commonbattle.actor.ActorContext context, ShopStockReserveResponse response) {
                throw new AssertionError("unexpected success");
            }

            @Override
            public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                failure.set(error);
            }
        });
        RuntimeException error = new RuntimeException("rpc failed");
        async.reserveCallback.failure(error);

        assertNull(failure.get());
        executor.runNext();

        assertEquals(error, failure.get());
    }

    private static final class RecordingAsyncClient implements ShopStockAsyncClient {
        private ShopStockCallback<ShopStockReserveResponse> reserveCallback;

        @Override
        public void reserve(
                String reservationId,
                String sku,
                int count,
                ShopStockCallback<ShopStockReserveResponse> callback
        ) {
            reserveCallback = callback;
        }

        @Override
        public void release(
                String reservationId,
                String sku,
                int count,
                ShopStockCallback<ShopStockReleaseResponse> callback
        ) {
        }

        @Override
        public void remaining(String sku, ShopStockCallback<ShopStockRemainingResponse> callback) {
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        private void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
