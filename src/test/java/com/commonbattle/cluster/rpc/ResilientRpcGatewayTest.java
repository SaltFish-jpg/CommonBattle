package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.example.cross.SceneOperations;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
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

            gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "hello", String.class), callback);

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

        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "one", String.class), first);
        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "two", String.class), second);
        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "three", String.class), third);

        assertEquals(RpcCircuitState.OPEN, gateway.state("SCENE", SceneOperations.ENTER));
        assertInstanceOf(RpcCircuitOpenException.class, third.failure.get());
        assertEquals(1, gateway.stats().shortCircuited());
        assertEquals(1, gateway.stats().openedCircuits());
        assertEquals(1, gateway.stats().circuits());
        assertEquals(1, gateway.stats().openCircuits());
    }

    @Test
    void closedGatewayRejectsNewCallsWithoutDelegateAttempt() {
        AtomicInteger calls = new AtomicInteger();
        RpcGateway delegate = new RpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                calls.incrementAndGet();
                callback.failure(new IllegalStateException("should not call"));
            }
        };
        ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                RpcRetryPolicy.noRetry(),
                new RpcCircuitBreakerConfig(2, Duration.ofSeconds(5))
        );
        RecordingCallback<String> callback = new RecordingCallback<>();

        gateway.close();
        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "closed", String.class), callback);

        assertEquals(0, calls.get());
        assertInstanceOf(IllegalStateException.class, callback.failure.get());
        assertEquals(1, gateway.stats().rejectedAfterClose());
        assertEquals(0, gateway.stats().attempts());
    }

    @Test
    void preservesCallOptionsAndIdempotencyKeyAcrossRetries() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<RpcCallOptions> firstOptions = new AtomicReference<>();
        AtomicReference<RpcCallOptions> secondOptions = new AtomicReference<>();
        OptionedRpcGateway delegate = new OptionedRpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                throw new AssertionError("optioned call expected");
            }

            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback, RpcCallOptions options) {
                int attempt = calls.incrementAndGet();
                if (attempt == 1) {
                    firstOptions.set(options);
                    callback.failure(new IllegalStateException("temporary"));
                    return;
                }
                secondOptions.set(options);
                @SuppressWarnings("unchecked")
                T response = (T) "ok";
                callback.success(response);
            }
        };
        RpcCallOptions options = RpcCallOptions.of(Duration.ofSeconds(1))
                .withIdempotencyKey("player-1:purchase-7");
        try (ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                new RpcRetryPolicy(2, Duration.ZERO),
                new RpcCircuitBreakerConfig(5, Duration.ofSeconds(1))
        )) {
            RecordingCallback<String> callback = new RecordingCallback<>();

            gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "hello", String.class), callback, options);

            assertTrue(callback.awaitSuccess());
            assertEquals(options.idempotencyKey(), firstOptions.get().idempotencyKey());
            assertEquals(options.requiredTargetMetadata(), firstOptions.get().requiredTargetMetadata());
            assertTrue(firstOptions.get().timeout().compareTo(options.timeout()) <= 0);
            assertTrue(secondOptions.get().timeout().compareTo(firstOptions.get().timeout()) <= 0);
            assertEquals("player-1:purchase-7", secondOptions.get().idempotencyKey());
        }
    }

    @Test
    void structuredBusinessFailureDoesNotRetryOrOpenCircuit() {
        AtomicInteger calls = new AtomicInteger();
        RpcGateway delegate = new RpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                calls.incrementAndGet();
                callback.failure(RpcStructuredException.rejected("insufficient_currency", Duration.ZERO));
            }
        };
        try (ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                new RpcRetryPolicy(3, Duration.ZERO),
                new RpcCircuitBreakerConfig(1, Duration.ofSeconds(5))
        )) {
            RecordingCallback<String> callback = new RecordingCallback<>();

            gateway.call(new RpcRequest<>("CENTER", "shop.buy", "order", String.class), callback);

            assertEquals(1, calls.get());
            assertInstanceOf(RpcStructuredException.class, callback.failure.get());
            assertEquals(RpcCircuitState.CLOSED, gateway.state("CENTER", "shop.buy"));
            assertEquals(0, gateway.stats().retries());
        }
    }

    @Test
    void pendingCapacityRejectionDoesNotAmplifyIntoRetries() {
        AtomicInteger calls = new AtomicInteger();
        RpcGateway delegate = new RpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                calls.incrementAndGet();
                callback.failure(new RpcRejectedException(10));
            }
        };
        try (ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                new RpcRetryPolicy(3, Duration.ZERO),
                new RpcCircuitBreakerConfig(5, Duration.ofSeconds(5))
        )) {
            RecordingCallback<String> callback = new RecordingCallback<>();

            gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "hello", String.class), callback);

            assertEquals(1, calls.get());
            assertInstanceOf(RpcRejectedException.class, callback.failure.get());
            assertEquals(0, gateway.stats().retries());
        }
    }

    @Test
    void retryDelayCannotExceedWholeCallTimeoutBudget() {
        AtomicInteger calls = new AtomicInteger();
        OptionedRpcGateway delegate = new OptionedRpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                calls.incrementAndGet();
                callback.failure(new IllegalStateException("temporary"));
            }

            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback, RpcCallOptions options) {
                call(request, callback);
            }
        };
        try (ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                new RpcRetryPolicy(2, Duration.ofSeconds(1)),
                new RpcCircuitBreakerConfig(5, Duration.ofSeconds(5))
        )) {
            RecordingCallback<String> callback = new RecordingCallback<>();

            gateway.call(
                    new RpcRequest<>("SCENE", SceneOperations.ENTER, "hello", String.class),
                    callback,
                    RpcCallOptions.of(Duration.ofMillis(20))
            );

            assertEquals(1, calls.get());
            assertInstanceOf(RpcTimeoutException.class, callback.failure.get());
            assertEquals(0, gateway.stats().retries());
        }
    }

    @Test
    void closeFailsCallWaitingForScheduledRetryAndCancelsRetry() {
        AtomicInteger calls = new AtomicInteger();
        RpcGateway delegate = new RpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                calls.incrementAndGet();
                callback.failure(new IllegalStateException("temporary"));
            }
        };
        ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                new RpcRetryPolicy(2, Duration.ofHours(1)),
                new RpcCircuitBreakerConfig(5, Duration.ofSeconds(5))
        );
        RecordingCallback<String> callback = new RecordingCallback<>();

        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "pending", String.class), callback);
        assertEquals(1, gateway.stats().retries());

        gateway.close();

        assertInstanceOf(IllegalStateException.class, callback.failure.get());
        assertEquals("RPC resilience gateway is closed", callback.failure.get().getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectedRetrySchedulerCompletesCallWithFailure() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.shutdownNow();
        RpcGateway delegate = new RpcGateway() {
            @Override
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                callback.failure(new IllegalStateException("temporary"));
            }
        };
        ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                new RpcRetryPolicy(2, Duration.ZERO),
                new RpcCircuitBreakerConfig(5, Duration.ofSeconds(5)),
                Clock.systemUTC(),
                scheduler
        );
        RecordingCallback<String> callback = new RecordingCallback<>();

        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "pending", String.class), callback);

        assertInstanceOf(RejectedExecutionException.class, callback.failure.get());
        assertEquals(1, gateway.stats().attempts());
        assertEquals(1, gateway.stats().retries());
        gateway.close();
    }

    @Test
    void closeFailsInFlightCallAndIgnoresLateDelegateResponse() {
        AtomicReference<RpcCallback<String>> delegateCallback = new AtomicReference<>();
        RpcGateway delegate = new RpcGateway() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                delegateCallback.set((RpcCallback<String>) callback);
            }
        };
        ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                RpcRetryPolicy.noRetry(),
                new RpcCircuitBreakerConfig(5, Duration.ofSeconds(5))
        );
        RecordingCallback<String> callback = new RecordingCallback<>();
        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "pending", String.class), callback);

        gateway.close();
        delegateCallback.get().success("late");

        assertInstanceOf(IllegalStateException.class, callback.failure.get());
        assertEquals(null, callback.success.get());
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
        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "one", String.class), new RecordingCallback<>());
        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "two", String.class), new RecordingCallback<>());
        assertEquals(RpcCircuitState.OPEN, gateway.state("SCENE", SceneOperations.ENTER));

        clock.advance(Duration.ofSeconds(5));
        RecordingCallback<String> probe = new RecordingCallback<>();
        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "probe", String.class), probe);

        assertEquals("recovered", probe.success.get());
        assertEquals(RpcCircuitState.CLOSED, gateway.state("SCENE", SceneOperations.ENTER));
    }

    @Test
    void halfOpenCircuitAllowsOnlyOneConcurrentProbe() {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<RpcCallback<String>> probeCallback = new AtomicReference<>();
        RpcGateway delegate = new RpcGateway() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
                if (calls.incrementAndGet() == 1) {
                    callback.failure(new IllegalStateException("down"));
                    return;
                }
                probeCallback.set((RpcCallback<String>) callback);
            }
        };
        ResilientRpcGateway gateway = new ResilientRpcGateway(
                delegate,
                RpcRetryPolicy.noRetry(),
                new RpcCircuitBreakerConfig(1, Duration.ofSeconds(5)),
                clock,
                Executors.newSingleThreadScheduledExecutor()
        );
        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "open", String.class),
                new RecordingCallback<>());
        clock.advance(Duration.ofSeconds(5));
        RecordingCallback<String> firstProbe = new RecordingCallback<>();
        RecordingCallback<String> concurrentProbe = new RecordingCallback<>();

        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "probe-1", String.class), firstProbe);
        gateway.call(new RpcRequest<>("SCENE", SceneOperations.ENTER, "probe-2", String.class), concurrentProbe);

        assertEquals(2, calls.get());
        assertEquals(RpcCircuitState.HALF_OPEN, gateway.state("SCENE", SceneOperations.ENTER));
        assertInstanceOf(RpcCircuitOpenException.class, concurrentProbe.failure.get());
        probeCallback.get().success("ok");
        assertEquals("ok", firstProbe.success.get());
        assertEquals(RpcCircuitState.CLOSED, gateway.state("SCENE", SceneOperations.ENTER));
        gateway.close();
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
