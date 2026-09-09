package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceMetadata;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * 玩家维度灰度路由策略。
 * 白名单玩家稳定进入 grayTag；其它玩家按 playerId 哈希百分比进入 grayTag，剩余进入 stableTag。
 */
public final class PlayerGrayRoutePolicy implements RpcRoutePolicy {
    private final PlayerGrayRouteConfig config;
    private final PlayerRouteKeyExtractor routeKeys;

    public PlayerGrayRoutePolicy(PlayerGrayRouteConfig config) {
        this(config, PlayerRouteKeyExtractor.payloadPlayerIdMethod());
    }

    public PlayerGrayRoutePolicy(PlayerGrayRouteConfig config, PlayerRouteKeyExtractor routeKeys) {
        this.config = Objects.requireNonNull(config, "config");
        this.routeKeys = Objects.requireNonNull(routeKeys, "routeKeys");
    }

    @Override
    public RpcCallOptions optionsFor(RpcRequest<?> request, RpcCallOptions baseOptions) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(baseOptions, "baseOptions");
        if (!config.enabled() || !appliesTo(request)) {
            return baseOptions;
        }
        OptionalLong playerId = routeKeys.playerId(request);
        String tag = playerId.isPresent() && gray(playerId.getAsLong()) ? config.grayTag() : config.stableTag();
        return baseOptions.withRequiredTargetMetadata(ServiceMetadata.ROUTE_TAG, tag);
    }

    private boolean appliesTo(RpcRequest<?> request) {
        return config.operations().isEmpty() || config.operations().contains(request.operation());
    }

    private boolean gray(long playerId) {
        if (config.playerWhitelist().contains(playerId)) {
            return true;
        }
        return config.grayPercent() > 0 && bucket(playerId) < config.grayPercent();
    }

    private static int bucket(long playerId) {
        long mixed = playerId;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        return Math.floorMod(Long.hashCode(mixed), 100);
    }
}
