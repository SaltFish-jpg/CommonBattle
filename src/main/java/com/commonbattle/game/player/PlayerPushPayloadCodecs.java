package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;

import java.util.Objects;

/**
 * 玩家客户端业务推送 payload 的 codec 注册入口。
 * 快照类前期用 protostuff bean 承载，等协议稳定后可替换为手写 protobuf codec。
 */
public final class PlayerPushPayloadCodecs {
    private PlayerPushPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        registry.registerProtostuffBean(PlayerPushPayloads.BagSnapshotPayload.class);
        registry.registerProtostuffBean(PlayerPushPayloads.ActivityProgressPayload.class);
        registry.registerProtostuffBean(PlayerPushPayloads.GrowthSnapshotPayload.class);
        registry.registerProtostuffBean(PlayerPushPayloads.ShopSnapshotPayload.class);
        registry.registerProtostuffBean(PlayerPushPayloads.BattleSnapshotPayload.class);
        registry.registerProtostuffBean(PlayerPushPayloads.TaskProgressPayload.class);
        registry.registerProtostuffBean(PlayerPushPayloads.AchievementProgressPayload.class);
        return registry;
    }
}
