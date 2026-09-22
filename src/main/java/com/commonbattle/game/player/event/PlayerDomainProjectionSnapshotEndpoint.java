package com.commonbattle.game.player.event;

import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;

/**
 * 玩家领域投影快照 RPC 端点。
 */
public final class PlayerDomainProjectionSnapshotEndpoint {
    private final PlayerDomainProjectionSnapshotReader reader;

    public PlayerDomainProjectionSnapshotEndpoint(PlayerDomainProjectionSnapshotReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(PlayerDomainProjectionSnapshotOperations.GET, (request, responder) -> {
            PlayerDomainProjectionSnapshotRequest payload =
                    (PlayerDomainProjectionSnapshotRequest) request.payload();
            responder.success(reader.find(payload.playerId())
                    .map(PlayerDomainProjectionSnapshotResponse::found)
                    .orElseGet(PlayerDomainProjectionSnapshotResponse::missing));
        });
    }
}
