package com.commonbattle.game.social;

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
 * 基于 RPC 的联盟快照读取器。
 * 该同步适配层只应在修复工作线程或启动补偿中使用，不应运行在 Netty 回调线程。
 */
public final class RemoteAllianceSnapshotReader implements AllianceSnapshotReader {
    private final RpcGateway gateway;
    private final Duration timeout;

    public RemoteAllianceSnapshotReader(RpcGateway gateway, Duration timeout) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public Optional<AllianceSnapshot> find(long allianceId) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AllianceSnapshotResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        gateway.call(new RpcRequest<>(
                ServiceKind.GAME.name(),
                AllianceSnapshotOperations.GET,
                new AllianceSnapshotRequest(allianceId),
                AllianceSnapshotResponse.class
        ), new RpcCallback<>() {
            @Override
            public void success(AllianceSnapshotResponse result) {
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
            throw new IllegalStateException("Failed to read alliance snapshot", failure.get());
        }
        AllianceSnapshotResponse result = response.get();
        return result == null ? Optional.empty() : result.optionalSnapshot();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Alliance snapshot request timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading alliance snapshot", e);
        }
    }
}
