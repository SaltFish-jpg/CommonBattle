package com.commonbattle.game.social;

import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;

/**
 * 好友快照 RPC 端点。
 * 好友 owner 服务绑定该端点后，Scene、Chat 等服务可在事件缺口时回源修复本地好友视图。
 */
public final class FriendSnapshotEndpoint {
    private final FriendSnapshotReader reader;

    public FriendSnapshotEndpoint(FriendSnapshotReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(FriendSnapshotOperations.GET, (request, responder) -> {
            FriendSnapshotRequest payload = (FriendSnapshotRequest) request.payload();
            responder.success(reader.find(payload.playerId())
                    .map(FriendSnapshotResponse::found)
                    .orElseGet(FriendSnapshotResponse::missing));
        });
    }
}
