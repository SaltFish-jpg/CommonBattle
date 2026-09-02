package com.commonbattle.game.config;

import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;

/**
 * 配置中心 RPC 端点。
 * 业务进程本地缓存发现事件缺口后，可通过该端点拉取完整快照恢复。
 */
public final class GameConfigCenterEndpoint {
    private final GameConfigCenterPublisher publisher;

    public GameConfigCenterEndpoint(GameConfigCenterPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(GameConfigOperations.SNAPSHOT, (request, responder) -> responder.success(publisher.snapshot()));
    }
}
