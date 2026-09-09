package com.commonbattle.game.shop;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class RemoteShopStockAsyncClientTest {
    @Test
    void reserveReturnsThroughRpcCallbackWithoutWaiting() {
        RecordingRpcGateway gateway = new RecordingRpcGateway();
        RemoteShopStockAsyncClient client = new RemoteShopStockAsyncClient(gateway);
        AtomicReference<ShopStockReserveResponse> response = new AtomicReference<>();

        client.reserve("order-10001-1", "limited_pack", 1, new ShopStockCallback<>() {
            @Override
            public void success(ShopStockReserveResponse result) {
                response.set(result);
            }

            @Override
            public void failure(Throwable error) {
                throw new AssertionError(error);
            }
        });

        assertNull(response.get());
        assertEquals(ServiceKind.CENTER.name(), gateway.request.target());
        assertEquals(ShopStockOperations.RESERVE, gateway.request.operation());
        ShopStockReserveRequest payload = assertInstanceOf(ShopStockReserveRequest.class, gateway.request.payload());
        assertEquals("order-10001-1", payload.reservationId());
        assertEquals("limited_pack", payload.sku());
        gateway.succeed(new ShopStockReserveResponse(true, 3));

        assertEquals(new ShopStockReserveResponse(true, 3), response.get());
    }

    private static final class RecordingRpcGateway implements RpcGateway {
        private RpcRequest<?> request;
        private RpcCallback<?> callback;

        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            this.request = request;
            this.callback = callback;
        }

        @SuppressWarnings("unchecked")
        private <T> void succeed(T response) {
            ((RpcCallback<T>) callback).success(response);
        }
    }
}
