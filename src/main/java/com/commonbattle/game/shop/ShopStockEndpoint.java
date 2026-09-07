package com.commonbattle.game.shop;

import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;

/**
 * 中心商店库存 RPC 端点。
 * 绑定到 Center 服后，Game 服可通过 RemoteShopStockRepository 做全服库存 CAS。
 */
public final class ShopStockEndpoint {
    private final ShopStockRepository stocks;

    public ShopStockEndpoint(ShopStockRepository stocks) {
        this.stocks = Objects.requireNonNull(stocks, "stocks");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(ShopStockOperations.RESERVE, (request, responder) -> {
            ShopStockReserveRequest payload = (ShopStockReserveRequest) request.payload();
            boolean reserved = stocks.reserve(payload.reservationId(), payload.sku(), payload.count());
            responder.success(new ShopStockReserveResponse(reserved, stocks.remaining(payload.sku())));
        });
        gateway.handle(ShopStockOperations.RELEASE, (request, responder) -> {
            ShopStockReleaseRequest payload = (ShopStockReleaseRequest) request.payload();
            stocks.release(payload.reservationId(), payload.sku(), payload.count());
            responder.success(new ShopStockReleaseResponse(stocks.remaining(payload.sku())));
        });
        gateway.handle(ShopStockOperations.REMAINING, (request, responder) -> {
            ShopStockRemainingRequest payload = (ShopStockRemainingRequest) request.payload();
            responder.success(new ShopStockRemainingResponse(stocks.remaining(payload.sku())));
        });
    }
}
