package com.commonbattle.cluster.boot;

import com.commonbattle.actor.agent.migration.AgentMigrationPayloadCodecs;
import com.commonbattle.actor.agent.remote.AgentDirectoryPayloadCodecs;
import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RegistryPayloadCodecs;
import com.commonbattle.example.cross.CrossPayloadCodecs;
import com.commonbattle.game.chat.ChatPayloadCodecs;
import com.commonbattle.game.player.PlayerBusinessCommandPayloadCodecs;
import com.commonbattle.game.player.PlayerPushPayloadCodecs;
import com.commonbattle.game.session.PlayerClientPayloadCodecs;
import com.commonbattle.game.shop.ShopStockPayloadCodecs;

/**
 * 服务启动时共享的 payload codec 装配。
 */
public final class BootPayloadCodecs {
    private BootPayloadCodecs() {
    }

    public static PayloadCodecRegistry clusterServer() {
        return registerCommon(CrossPayloadCodecs.create());
    }

    public static PayloadCodecRegistry gameServer() {
        return PlayerClientPayloadCodecs.registerTo(registerCommon(CrossPayloadCodecs.create()));
    }

    public static PayloadCodecRegistry centerServer() {
        return registerCommon(PayloadCodecRegistry.commonDefaults());
    }

    private static PayloadCodecRegistry registerCommon(PayloadCodecRegistry registry) {
        return ClusterEventPayloadCodecs.registerTo(
                PlayerPushPayloadCodecs.registerTo(ChatPayloadCodecs.registerTo(ShopStockPayloadCodecs.registerTo(
                        PlayerBusinessCommandPayloadCodecs.registerTo(AgentMigrationPayloadCodecs.registerTo(
                                AgentDirectoryPayloadCodecs.registerTo(RegistryPayloadCodecs.registerTo(registry))
                        ))
                )))
        );
    }
}
