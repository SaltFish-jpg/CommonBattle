package com.commonbattle.game.profile;

import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;

/**
 * 玩家基础资料快照 RPC 端点。
 * Game/Profile owner 服务绑定该端点后，Scene、Chat 等服务可在本地 cache 缺口时回源读取。
 */
public final class ProfileSnapshotEndpoint {
    private final ProfileSnapshotReader reader;

    public ProfileSnapshotEndpoint(ProfileSnapshotReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(ProfileSnapshotOperations.GET, (request, responder) -> {
            ProfileSnapshotRequest payload = (ProfileSnapshotRequest) request.payload();
            responder.success(reader.find(payload.playerId())
                    .map(ProfileSnapshotResponse::found)
                    .orElseGet(ProfileSnapshotResponse::missing));
        });
    }
}
