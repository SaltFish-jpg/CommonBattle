package com.commonbattle.game.shop;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;

import java.util.Objects;

/**
 * 基于 RPC 的商店库存异步客户端。
 * 它不等待远端结果，适合玩家 Actor 发起跨服库存操作后释放工作线程。
 */
public final class RemoteShopStockAsyncClient implements ShopStockAsyncClient {
    private final RpcGateway gateway;

    public RemoteShopStockAsyncClient(RpcGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public void reserve(
            String reservationId,
            String sku,
            int count,
            ShopStockCallback<ShopStockReserveResponse> callback
    ) {
        call(
                ShopStockOperations.RESERVE,
                new ShopStockReserveRequest(reservationId, sku, count),
                ShopStockReserveResponse.class,
                callback
        );
    }

    @Override
    public void release(
            String reservationId,
            String sku,
            int count,
            ShopStockCallback<ShopStockReleaseResponse> callback
    ) {
        call(
                ShopStockOperations.RELEASE,
                new ShopStockReleaseRequest(reservationId, sku, count),
                ShopStockReleaseResponse.class,
                callback
        );
    }

    @Override
    public void remaining(String sku, ShopStockCallback<ShopStockRemainingResponse> callback) {
        call(
                ShopStockOperations.REMAINING,
                new ShopStockRemainingRequest(sku),
                ShopStockRemainingResponse.class,
                callback
        );
    }

    private <T> void call(String operation, Object payload, Class<T> responseType, ShopStockCallback<T> callback) {
        Objects.requireNonNull(callback, "callback");
        gateway.call(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                operation,
                payload,
                responseType
        ), new RpcCallback<>() {
            @Override
            public void success(T result) {
                callback.success(result);
            }

            @Override
            public void failure(Throwable error) {
                callback.failure(error);
            }
        });
    }
}
