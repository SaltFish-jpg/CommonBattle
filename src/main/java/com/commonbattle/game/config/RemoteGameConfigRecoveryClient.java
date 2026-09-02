package com.commonbattle.game.config;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 业务进程本地配置缓存的远程恢复客户端。
 * 它只负责发起 RPC 和应用快照，不在网络回调线程中执行任何玩家业务逻辑。
 */
public final class RemoteGameConfigRecoveryClient implements GameConfigRecoveryPort {
    private final RpcGateway gateway;
    private final LocalGameConfigCache cache;

    public RemoteGameConfigRecoveryClient(RpcGateway gateway, LocalGameConfigCache cache) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public void recover(Consumer<GameConfigApplyResult> callback) {
        Objects.requireNonNull(callback, "callback");
        gateway.call(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                GameConfigOperations.SNAPSHOT,
                new GameConfigSnapshotRequest(cache.appliedEventRevision()),
                GameConfigSnapshot.class
        ), new RpcCallback<>() {
            @Override
            public void success(GameConfigSnapshot response) {
                callback.accept(cache.applySnapshot(response));
            }

            @Override
            public void failure(Throwable error) {
                callback.accept(GameConfigApplyResult.recoveryFailed(
                        cache.appliedEventRevision(),
                        error.getMessage()
                ));
            }
        });
    }
}
