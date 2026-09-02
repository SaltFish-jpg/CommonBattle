package com.commonbattle.game.profile;

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
 * 基于 RPC 的玩家基础资料快照读取器。
 * 该同步适配层应在恢复工作线程或启动预热中使用，不应直接运行在 Netty 回调线程。
 */
public final class RemoteProfileSnapshotReader implements ProfileSnapshotReader {
    private final RpcGateway gateway;
    private final Duration timeout;

    public RemoteProfileSnapshotReader(RpcGateway gateway, Duration timeout) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public Optional<PlayerProfileSnapshot> find(long playerId) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ProfileSnapshotResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        gateway.call(new RpcRequest<>(
                ServiceKind.GAME.name(),
                ProfileSnapshotOperations.GET,
                new ProfileSnapshotRequest(playerId),
                ProfileSnapshotResponse.class
        ), new RpcCallback<>() {
            @Override
            public void success(ProfileSnapshotResponse result) {
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
            throw new IllegalStateException("Failed to read profile snapshot", failure.get());
        }
        ProfileSnapshotResponse result = response.get();
        return result == null ? Optional.empty() : result.optionalSnapshot();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Profile snapshot request timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading profile snapshot", e);
        }
    }
}
