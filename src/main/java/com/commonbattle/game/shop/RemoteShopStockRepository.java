package com.commonbattle.game.shop;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 RPC 的商店全服库存存储。
 * 该同步适配层应在玩家 Agent 业务线程中短时调用，生产环境需配合超时、熔断和幂等购买单号。
 */
public final class RemoteShopStockRepository implements ShopStockRepository {
    private final RpcGateway gateway;
    private final Duration timeout;

    public RemoteShopStockRepository(RpcGateway gateway, Duration timeout) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public boolean reserve(String sku, int count) {
        return reserve("", sku, count);
    }

    @Override
    public boolean reserve(String reservationId, String sku, int count) {
        ShopStockReserveResponse response = call(
                ShopStockOperations.RESERVE,
                new ShopStockReserveRequest(reservationId, sku, count),
                ShopStockReserveResponse.class
        );
        return response.reserved();
    }

    @Override
    public void release(String sku, int count) {
        release("", sku, count);
    }

    @Override
    public void release(String reservationId, String sku, int count) {
        call(
                ShopStockOperations.RELEASE,
                new ShopStockReleaseRequest(reservationId, sku, count),
                ShopStockReleaseResponse.class
        );
    }

    @Override
    public int remaining(String sku) {
        ShopStockRemainingResponse response = call(
                ShopStockOperations.REMAINING,
                new ShopStockRemainingRequest(sku),
                ShopStockRemainingResponse.class
        );
        return response.remaining();
    }

    private <T> T call(String operation, Object payload, Class<T> responseType) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        gateway.call(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                operation,
                payload,
                responseType
        ), new RpcCallback<>() {
            @Override
            public void success(T result) {
                response.set(result);
                latch.countDown();
            }

            @Override
            public void failure(Throwable error) {
                failure.set(error);
                latch.countDown();
            }
        });
        await(latch, operation);
        if (failure.get() != null) {
            throw new IllegalStateException("Failed to call shop stock operation " + operation, failure.get());
        }
        return response.get();
    }

    private void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Shop stock operation timed out: " + operation);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling shop stock operation " + operation, e);
        }
    }
}
