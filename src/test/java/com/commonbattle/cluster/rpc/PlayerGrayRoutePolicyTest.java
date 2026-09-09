package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayerGrayRoutePolicyTest {
    @Test
    void whitelistedPlayerRoutesToGrayTag() {
        PlayerGrayRoutePolicy policy = new PlayerGrayRoutePolicy(new PlayerGrayRouteConfig(
                true,
                "stable",
                "gray",
                0,
                Set.of(10001L),
                Set.of("scene.enter")
        ));

        RpcCallOptions options = policy.optionsFor(
                request("scene.enter", new PlayerPayload(10001L)),
                RpcCallOptions.of(Duration.ofSeconds(1))
        );

        assertEquals("gray", options.requiredTargetMetadata().get(ServiceMetadata.ROUTE_TAG));
    }

    @Test
    void percentRoutesAllPlayersToGrayWhenSetToHundred() {
        PlayerGrayRoutePolicy policy = new PlayerGrayRoutePolicy(new PlayerGrayRouteConfig(
                true,
                "stable",
                "gray",
                100,
                Set.of(),
                Set.of("scene.enter")
        ));

        RpcCallOptions options = policy.optionsFor(
                request("scene.enter", new PlayerPayload(20002L)),
                RpcCallOptions.of(Duration.ofSeconds(1))
        );

        assertEquals("gray", options.requiredTargetMetadata().get(ServiceMetadata.ROUTE_TAG));
    }

    @Test
    void nonGrayPlayerRoutesToStableTag() {
        PlayerGrayRoutePolicy policy = new PlayerGrayRoutePolicy(new PlayerGrayRouteConfig(
                true,
                "stable",
                "gray",
                0,
                Set.of(),
                Set.of("scene.enter")
        ));

        RpcCallOptions options = policy.optionsFor(
                request("scene.enter", new PlayerPayload(20002L)),
                RpcCallOptions.of(Duration.ofSeconds(1))
        );

        assertEquals("stable", options.requiredTargetMetadata().get(ServiceMetadata.ROUTE_TAG));
    }

    @Test
    void operationsLimitKeepsUnmatchedRequestUntouched() {
        PlayerGrayRoutePolicy policy = new PlayerGrayRoutePolicy(new PlayerGrayRouteConfig(
                true,
                "stable",
                "gray",
                100,
                Set.of(),
                Set.of("scene.enter")
        ));

        RpcCallOptions options = policy.optionsFor(
                request("shop.stock.reserve", new PlayerPayload(10001L)),
                RpcCallOptions.of(Duration.ofSeconds(1))
        );

        assertFalse(options.requiredTargetMetadata().containsKey(ServiceMetadata.ROUTE_TAG));
    }

    @Test
    void disabledPolicyKeepsRequestUntouched() {
        PlayerGrayRoutePolicy policy = new PlayerGrayRoutePolicy(PlayerGrayRouteConfig.disabled());

        RpcCallOptions options = policy.optionsFor(
                request("scene.enter", new PlayerPayload(10001L)),
                RpcCallOptions.of(Duration.ofSeconds(1))
        );

        assertFalse(options.requiredTargetMetadata().containsKey(ServiceMetadata.ROUTE_TAG));
    }

    private static RpcRequest<String> request(String operation, Object payload) {
        return new RpcRequest<>("SCENE", operation, payload, String.class);
    }

    private record PlayerPayload(long playerId) {
    }
}
