package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilientRpcGatewayTest {
    @Test
    void retriesFailedCallAndReturnsSuccessfulAttempt() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        RpcGateway delegate = new RpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                if (calls.incrementAndGet() == 1) {
                    callback.failure(new IllegalStateException("temporary"));
                    return;
                }
                @SuppressWarnings("unchecked")
                T response = (T) "ok";
                callback.success(response);
            }
        };
        try (ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                new RpcRetryPolicy(2, Duration.ZERO),
                new RpcCircuitBreakerConfig(5, Duration.ofSeconds(1))
        )) {
            RecordingCallback<String> callback = new RecordingCallback<>();

            gateway.call(new RpcRequest<>("SCENE", "scene.enter", "hello", String.class), callback);

            assertTrue(callback.awaitSuccess());
            assertEquals("ok", callback.success.get());
            assertEquals(2, calls.get());
            assertEquals(1, gateway.stats().retries());
        }
    }

    @Test
    void opensCircuitAfterConsecutiveFailuresAndShortCircuitsNextCall() {
        RpcGateway delegate = new RpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                callback.failure(new IllegalStateException("down"));
            }
        };
        ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                RpcRetryPolicy.noRetry(),
                new RpcCircuitBreakerConfig(2, Duration.ofSeconds(5))
        );
        RecordingCallback<String> first = new RecordingCallback<>();
        RecordingCallback<String> second = new RecordingCallback<>();
        RecordingCallback<String> third = new RecordingCallback<>();

        gateway.call(new RpcRequest<>("SCENE", "scene.enter", "one", String.class), first);
        gateway.call(new RpcRequest<>("SCENE", "scene.enter", "two", String.class), second);
        gateway.call(new RpcRequest<>("SCENE", "scene.enter", "three", String.class), third);

        assertEquals(RpcCircuitState.OPEN, gateway.state("SCENE", "scene.enter"));
        assertInstanceOf(RpcCircuitOpenException.class, third.failure.get());
        assertEquals(1, gateway.stats().shortCircuited());
        assertEquals(1, gateway.stats().openedCircuits());
    }

    @Test
    void circuitAllowsProbeAfterOpenDurationAndClosesOnSuccess() {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        RpcGateway delegate = new RpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                if (calls.incrementAndGet() <= 2) {
                    callback.failure(new IllegalStateException("down"));
                    return;
                }
                @SuppressWarnings("unchecked")
                T response = (T) "recovered";
                callback.success(response);
            }
        };
        ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                RpcRetryPolicy.noRetry(),
                new RpcCircuitBreakerConfig(2, Duration.ofSeconds(5)),
                clock,
                Executors.newSingleThreadScheduledExecutor()
        );
        gateway.call(new RpcRequest<>("SCENE", "scene.enter", "one", String.class), new RecordingCallback<>());
        gateway.call(new RpcRequest<>("SCENE", "scene.enter", "two", String.class), new RecordingCallback<>());
        assertEquals(RpcCircuitState.OPEN, gateway.state("SCENE", "scene.enter"));

        clock.advance(Duration.ofSeconds(5));
        RecordingCallback<String> probe = new RecordingCallback<>();
        gateway.call(new RpcRequest<>("SCENE", "scene.enter", "probe", String.class), probe);

        assertEquals("recovered", probe.success.get());
        assertEquals(RpcCircuitState.CLOSED, gateway.state("SCENE", "scene.enter"));
    }

    private static final class RecordingCallback<T> implements RpcCallback<T> {
        private final AtomicReference<T> success = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch successLatch = new CountDownLatch(1);

        @Override
        public void success(T response) {
            success.set(response);
            successLatch.countDown();
        }

        @Override
        public void failure(Throwable error) {
            failure.set(error);
        }

        private boolean awaitSuccess() throws InterruptedException {
            return successLatch.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
