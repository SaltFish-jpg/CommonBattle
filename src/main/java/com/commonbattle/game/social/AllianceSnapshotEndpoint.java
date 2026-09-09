package com.commonbattle.game.social;

import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;

/**
 * 联盟快照 RPC 端点。
 * 联盟 owner 服务绑定该端点后，Scene、Chat 等服务可在事件缺口时回源修复本地关系视图。
 */
public final class AllianceSnapshotEndpoint {
    private final AllianceSnapshotReader reader;

    public AllianceSnapshotEndpoint(AllianceSnapshotReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(AllianceSnapshotOperations.GET, (request, responder) -> {
            AllianceSnapshotRequest payload = (AllianceSnapshotRequest) request.payload();
            responder.success(reader.find(payload.allianceId())
                    .map(AllianceSnapshotResponse::found)
                    .orElseGet(AllianceSnapshotResponse::missing));
        });
    }
}
