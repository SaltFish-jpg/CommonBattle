package com.commonbattle.game.config;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameConfigWarmupServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void warmupReturnsReadyWhenSnapshotRecoverySucceeds() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        GameConfigSnapshot snapshot = GameConfigSnapshot.activeOnly(1, GameConfigValidatorTest.validConfig(1));
        GameConfigWarmupService warmup = new GameConfigWarmupService(
                new RemoteGameConfigRecoveryClient(new SnapshotGateway(snapshot), cache),
                CLOCK
        );

        GameConfigWarmupResult result = warmup.warmup(Duration.ofSeconds(1));

        assertEquals(GameConfigWarmupStatus.READY, result.status());
        assertTrue(result.ready());
        assertEquals(1, cache.active().version());
    }

    @Test
    void warmupReturnsFailedWhenRecoveryCallbackFails() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        GameConfigWarmupService warmup = new GameConfigWarmupService(
                new RemoteGameConfigRecoveryClient(new FailingGateway(), cache),
                CLOCK
        );

        GameConfigWarmupResult result = warmup.warmup(Duration.ofSeconds(1));

        assertEquals(GameConfigWarmupStatus.FAILED, result.status());
        assertEquals(GameConfigApplyStatus.RECOVERY_FAILED, result.applyResult().status());
    }

    @Test
    void warmupReturnsTimeoutWhenGatewayNeverCallbacks() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        GameConfigWarmupService warmup = new GameConfigWarmupService(
                new RemoteGameConfigRecoveryClient(new SilentGateway(), cache),
                CLOCK
        );

        GameConfigWarmupResult result = warmup.warmup(Duration.ofMillis(1));

        assertEquals(GameConfigWarmupStatus.TIMEOUT, result.status());
    }

    private record SnapshotGateway(GameConfigSnapshot snapshot) implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            callback.success(request.responseType().cast(snapshot));
        }
    }

    private static final class FailingGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            callback.failure(new IllegalStateException("center unavailable"));
        }
    }

    private static final class SilentGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
