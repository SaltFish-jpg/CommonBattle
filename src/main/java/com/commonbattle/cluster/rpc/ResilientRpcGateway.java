package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * RPC 重试与熔断装饰器。
 * 它包在底层 RpcGateway 外面，失败时按策略重试，连续失败过多时对同 target + operation 快速失败。
 */
public final class ResilientRpcGateway implements RpcGateway, AutoCloseable {
    private final RpcGateway delegate;
    private final RpcRetryPolicy retryPolicy;
    private final RpcCircuitBreakerConfig circuitConfig;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Map<Key, Circuit> circuits = new ConcurrentHashMap<>();
    private final LongAdder attempts = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder shortCircuited = new LongAdder();
    private final LongAdder openedCircuits = new LongAdder();

    public ResilientRpcGateway(RpcGateway delegate, RpcRetryPolicy retryPolicy, RpcCircuitBreakerConfig circuitConfig) {
        this(delegate, retryPolicy, circuitConfig, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(new ResilienceThreadFactory()), true);
    }

    public ResilientRpcGateway(
            RpcGateway delegate,
            RpcRetryPolicy retryPolicy,
            RpcCircuitBreakerConfig circuitConfig,
            Clock clock,
            ScheduledExecutorService scheduler
    ) {
        this(delegate, retryPolicy, circuitConfig, clock, scheduler, false);
    }

    private ResilientRpcGateway(
            RpcGateway delegate,
            RpcRetryPolicy retryPolicy,
            RpcCircuitBreakerConfig circuitConfig,
            Clock clock,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.circuitConfig = Objects.requireNonNull(circuitConfig, "circuitConfig");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    @Override
    public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        Key key = new Key(request.target(), request.operation());
        Circuit circuit = circuits.computeIfAbsent(key, ignored -> new Circuit());
        if (!circuit.allow(clock.millis(), circuitConfig.openDuration().toMillis())) {
            shortCircuited.increment();
            callback.failure(new RpcCircuitOpenException(request));
            return;
        }
        attempt(request, callback, circuit, 1);
    }

    public RpcCircuitState state(String target, String operation) {
        Circuit circuit = circuits.get(new Key(target, operation));
        if (circuit == null) {
            return RpcCircuitState.CLOSED;
        }
        return circuit.state(clock.millis(), circuitConfig.openDuration().toMillis());
    }

    public RpcResilienceStats stats() {
        return new RpcResilienceStats(attempts.sum(), retries.sum(), shortCircuited.sum(), openedCircuits.sum());
    }

    private <T> void attempt(RpcRequest<T> request, RpcCallback<T> callback, Circuit circuit, int attempt) {
        attempts.increment();
        delegate.call(request, new RpcCallback<>() {
            @Override
            public void success(T response) {
                circuit.recordSuccess();
                callback.success(response);
            }

            @Override
            public void failure(Throwable error) {
                boolean opened = circuit.recordFailure(
                        circuitConfig.failureThreshold(),
                        clock.millis(),
                        circuitConfig.openDuration().toMillis()
                );
                if (opened) {
                    openedCircuits.increment();
                }
                if (attempt < retryPolicy.maxAttempts() && circuit.allow(clock.millis(), circuitConfig.openDuration().toMillis())) {
                    retries.increment();
                    scheduler.schedule(
                            () -> attempt(request, callback, circuit, attempt + 1),
                            retryPolicy.retryDelay().toMillis(),
                            TimeUnit.MILLISECONDS
                    );
                    return;
                }
                callback.failure(error);
            }
        });
    }

    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private record Key(String target, String operation) {
    }

    private static final class Circuit {
        private int consecutiveFailures;
        private long openedAtMillis = -1;

        synchronized boolean allow(long nowMillis, long openDurationMillis) {
            if (openedAtMillis < 0) {
                return true;
            }
            return nowMillis - openedAtMillis >= openDurationMillis;
        }

        synchronized RpcCircuitState state(long nowMillis, long openDurationMillis) {
            return allow(nowMillis, openDurationMillis) ? RpcCircuitState.CLOSED : RpcCircuitState.OPEN;
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            openedAtMillis = -1;
        }

        synchronized boolean recordFailure(int threshold, long nowMillis, long openDurationMillis) {
            if (openedAtMillis >= 0 && nowMillis - openedAtMillis < openDurationMillis) {
                return false;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= threshold) {
                openedAtMillis = nowMillis;
                return true;
            }
            return false;
        }
    }

    private static final class ResilienceThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "common-battle-rpc-resilience");
            thread.setDaemon(true);
            return thread;
        }
    }
}
