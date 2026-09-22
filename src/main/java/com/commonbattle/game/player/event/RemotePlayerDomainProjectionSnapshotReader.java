package com.commonbattle.game.player.event;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 RPC 的玩家领域投影快照读取器。
 * 该同步适配层只应运行在修复工作线程，不应运行在 Netty 回调线程或业务 Actor 邮箱。
 */
public final class RemotePlayerDomainProjectionSnapshotReader implements PlayerDomainProjectionSnapshotReader {
    private final RpcGateway gateway;
    private final Duration timeout;

    public RemotePlayerDomainProjectionSnapshotReader(RpcGateway gateway, Duration timeout) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public Optional<PlayerDomainProjectionSnapshot> find(long playerId) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PlayerDomainProjectionSnapshotResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        gateway.call(new RpcRequest<>(
                ServiceKind.GAME.name(),
                PlayerDomainProjectionSnapshotOperations.GET,
                new PlayerDomainProjectionSnapshotRequest(playerId),
                PlayerDomainProjectionSnapshotResponse.class
        ), new RpcCallback<>() {
            @Override
            public void success(PlayerDomainProjectionSnapshotResponse result) {
                response.set(result);
                latch.countDown();
            }

            @Override
            public void failure(Throwable error) {
                failure.set(error);
                latch.countDown();
            }
        });
        await(latch);
        if (failure.get() != null) {
            throw new IllegalStateException("Failed to read player domain projection snapshot", failure.get());
        }
        PlayerDomainProjectionSnapshotResponse result = response.get();
        return result == null ? Optional.empty() : result.optionalSnapshot();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Player domain projection snapshot request timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading player domain projection snapshot", e);
        }
    }
}
