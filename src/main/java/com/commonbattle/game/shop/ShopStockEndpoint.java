package com.commonbattle.game.shop;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcResponder;
import com.commonbattle.cluster.rpc.RpcStructuredException;

import java.time.Duration;
import java.util.Objects;

/**
 * 中心商店库存 RPC 端点。
 * 绑定到 Center 服后，Game 服可通过 RemoteShopStockRepository 做全服库存 CAS。
 */
public final class ShopStockEndpoint {
    private final ShopStockRepository stocks;
    private final ActorSystem actors;
    private final String actorPrefix;

    public ShopStockEndpoint(ShopStockRepository stocks, ActorSystem actors, String actorPrefix) {
        this.stocks = Objects.requireNonNull(stocks, "stocks");
        this.actors = Objects.requireNonNull(actors, "actors");
        if (actorPrefix == null || actorPrefix.isBlank()) {
            throw new IllegalArgumentException("actorPrefix required");
        }
        this.actorPrefix = actorPrefix;
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(ShopStockOperations.RESERVE, (request, responder) -> {
            ShopStockReserveRequest payload = (ShopStockReserveRequest) request.payload();
            enqueue(payload.sku(), responder, () -> {
                boolean reserved = stocks.reserve(payload.reservationId(), payload.sku(), payload.count());
                responder.success(new ShopStockReserveResponse(reserved, stocks.remaining(payload.sku())));
            });
        });
        gateway.handle(ShopStockOperations.RELEASE, (request, responder) -> {
            ShopStockReleaseRequest payload = (ShopStockReleaseRequest) request.payload();
            enqueue(payload.sku(), responder, () -> {
                stocks.release(payload.reservationId(), payload.sku(), payload.count());
                responder.success(new ShopStockReleaseResponse(stocks.remaining(payload.sku())));
            });
        });
        gateway.handle(ShopStockOperations.REMAINING, (request, responder) -> {
            ShopStockRemainingRequest payload = (ShopStockRemainingRequest) request.payload();
            enqueue(payload.sku(), responder,
                    () -> responder.success(new ShopStockRemainingResponse(stocks.remaining(payload.sku()))));
        });
    }

    private void enqueue(String sku, RpcResponder responder, Runnable operation) {
        ActorRef actor = actors.actor(actorPrefix + ':' + sku);
        boolean accepted = actors.trySend(actor, ActorTask.categorized(ActorTaskCategory.DEFAULT, ignored -> {
            try {
                operation.run();
            } catch (Throwable error) {
                responder.failure(error);
            }
        }));
        if (!accepted) {
            responder.failure(RpcStructuredException.rejected(
                    "mailbox_pressure:shop_stock", Duration.ofMillis(50)
            ));
        }
    }
}
