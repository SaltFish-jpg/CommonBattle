package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * RPC 重试与熔断装饰器。
 * 它包在底层 RpcGateway 外面，失败时按策略重试，连续失败过多时对同 target + operation 快速失败。
 */
public final class ResilientRpcGateway implements OptionedRpcGateway, AutoCloseable {
    private final RpcGateway delegate;
    private final RpcRetryPolicy retryPolicy;
    private final RpcCircuitBreakerConfig circuitConfig;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Map<Key, Circuit> circuits = new ConcurrentHashMap<>();
    private final java.util.Set<CallState<?>> activeCalls = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final LongAdder attempts = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder shortCircuited = new LongAdder();
    private final LongAdder openedCircuits = new LongAdder();
    private final LongAdder rejectedAfterClose = new LongAdder();

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
        startCall(request, callback, null);
    }

    @Override
    public <T> void call(RpcRequest<T> request, RpcCallback<T> callback, RpcCallOptions options) {
        startCall(request, callback, Objects.requireNonNull(options, "options"));
    }

    private <T> void startCall(RpcRequest<T> request, RpcCallback<T> callback, RpcCallOptions options) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        if (!accepting.get()) {
            rejectedAfterClose.increment();
            callback.failure(new IllegalStateException("RPC resilience gateway is closed"));
            return;
        }
        Key key = new Key(request.target(), request.operation());
        Circuit circuit = circuits.computeIfAbsent(key, ignored -> new Circuit());
        if (!circuit.allow(clock.millis(), circuitConfig.openDuration().toMillis())) {
            shortCircuited.increment();
            callback.failure(new RpcCircuitOpenException(request));
            return;
        }
        CallState<T> call = new CallState<>(callback, options == null ? null : options.timeout());
        activeCalls.add(call);
        if (!accepting.get()) {
            rejectedAfterClose.increment();
            completeFailure(call, closedFailure());
            return;
        }
        attempt(request, call, circuit, options, 1);
    }

    public RpcCircuitState state(String target, String operation) {
        Circuit circuit = circuits.get(new Key(target, operation));
        if (circuit == null) {
            return RpcCircuitState.CLOSED;
        }
        return circuit.state(clock.millis(), circuitConfig.openDuration().toMillis());
    }

    public RpcResilienceStats stats() {
        long nowMillis = clock.millis();
        int openCircuits = Math.toIntExact(circuits.values().stream()
                .filter(circuit -> circuit.state(nowMillis, circuitConfig.openDuration().toMillis())
                        != RpcCircuitState.CLOSED)
                .count());
        return new RpcResilienceStats(
                attempts.sum(),
                retries.sum(),
                shortCircuited.sum(),
                openedCircuits.sum(),
                rejectedAfterClose.sum(),
                circuits.size(),
                openCircuits
        );
    }

    private <T> void attempt(
            RpcRequest<T> request,
            CallState<T> call,
            Circuit circuit,
            RpcCallOptions options,
            int attempt
    ) {
        if (call.completed.get()) {
            return;
        }
        if (!accepting.get()) {
            completeFailure(call, closedFailure());
            return;
        }
        RpcCallOptions attemptOptions = remainingOptions(call, options);
        if (options != null && attemptOptions == null) {
            circuit.releaseProbe();
            completeFailure(call, new RpcTimeoutException(request, options.timeout()));
            return;
        }
        attempts.increment();
        AtomicBoolean attemptCompleted = new AtomicBoolean();
        try {
            invokeDelegate(request, new RpcCallback<>() {
                @Override
                public void success(T response) {
                    if (attemptCompleted.compareAndSet(false, true)) {
                        completeSuccess(call, response, circuit);
                    }
                }

                @Override
                public void failure(Throwable error) {
                    if (attemptCompleted.compareAndSet(false, true)) {
                        handleFailure(request, call, circuit, options, attempt, error);
                    }
                }
            }, attemptOptions);
        } catch (RuntimeException error) {
            if (attemptCompleted.compareAndSet(false, true)) {
                handleFailure(request, call, circuit, options, attempt, error);
            }
        }
    }

    private <T> void invokeDelegate(
            RpcRequest<T> request,
            RpcCallback<T> callback,
            RpcCallOptions options
    ) {
        if (options == null) {
            delegate.call(request, callback);
            return;
        }
        if (!(delegate instanceof OptionedRpcGateway optioned)) {
            throw new IllegalStateException("RPC delegate does not support call options");
        }
        optioned.call(request, callback, options);
    }

    private <T> void handleFailure(
            RpcRequest<T> request,
            CallState<T> call,
            Circuit circuit,
            RpcCallOptions options,
            int attempt,
            Throwable error
    ) {
        if (call.completed.get()) {
            return;
        }
        if (error instanceof RpcStructuredException) {
            circuit.recordSuccess();
            completeFailure(call, error);
            return;
        }
        boolean opened = circuit.recordFailure(
                circuitConfig.failureThreshold(),
                clock.millis(),
                circuitConfig.openDuration().toMillis()
        );
        if (opened) {
            openedCircuits.increment();
        }
        if (accepting.get()
                && attempt < retryPolicy.maxAttempts()
                && !(error instanceof RpcRejectedException)
                && circuit.allow(clock.millis(), circuitConfig.openDuration().toMillis())) {
            if (!call.canRetryAfter(retryPolicy.retryDelay())) {
                Duration timeout = options == null ? retryPolicy.retryDelay() : options.timeout();
                completeFailure(call, new RpcTimeoutException(request, timeout));
                return;
            }
            retries.increment();
            scheduleRetry(request, call, circuit, options, attempt + 1, error);
            return;
        }
        completeFailure(call, error);
    }

    private <T> void scheduleRetry(
            RpcRequest<T> request,
            CallState<T> call,
            Circuit circuit,
            RpcCallOptions options,
            int nextAttempt,
            Throwable previousFailure
    ) {
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> attempt(request, call, circuit, options, nextAttempt),
                    retryPolicy.retryDelay().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            call.retryFuture = future;
            if (call.completed.get()) {
                future.cancel(false);
            }
        } catch (RejectedExecutionException error) {
            completeFailure(call, accepting.get() ? error : closedFailure());
        } catch (RuntimeException error) {
            error.addSuppressed(previousFailure);
            completeFailure(call, error);
        }
    }

    private <T> void completeSuccess(CallState<T> call, T response, Circuit circuit) {
        if (!call.completed.compareAndSet(false, true)) {
            return;
        }
        activeCalls.remove(call);
        call.cancelRetry();
        circuit.recordSuccess();
        call.callback.success(response);
    }

    private void completeFailure(CallState<?> call, Throwable error) {
        if (!call.completed.compareAndSet(false, true)) {
            return;
        }
        activeCalls.remove(call);
        call.cancelRetry();
        call.callback.failure(error);
    }

    private static RpcCallOptions remainingOptions(CallState<?> call, RpcCallOptions options) {
        if (options == null) {
            return null;
        }
        Duration remaining = call.remaining();
        if (remaining.isZero() || remaining.isNegative()) {
            return null;
        }
        return options.withTimeout(remaining);
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        try {
            activeCalls.forEach(call -> {
                try {
                    completeFailure(call, closedFailure());
                } catch (Throwable ignored) {
                }
            });
        } finally {
            if (ownsScheduler) {
                scheduler.shutdownNow();
            }
        }
    }

    private static IllegalStateException closedFailure() {
        return new IllegalStateException("RPC resilience gateway is closed");
    }

    private record Key(String target, String operation) {
    }

    private static final class CallState<T> {
        private final RpcCallback<T> callback;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final long deadlineNanos;
        private volatile ScheduledFuture<?> retryFuture;

        private CallState(RpcCallback<T> callback, Duration timeout) {
            this.callback = callback;
            this.deadlineNanos = timeout == null
                    ? Long.MAX_VALUE
                    : saturatingAdd(System.nanoTime(), timeoutNanos(timeout));
        }

        private void cancelRetry() {
            ScheduledFuture<?> future = retryFuture;
            if (future != null) {
                future.cancel(false);
            }
        }

        private Duration remaining() {
            if (deadlineNanos == Long.MAX_VALUE) {
                return Duration.ofNanos(Long.MAX_VALUE);
            }
            return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
        }

        private boolean canRetryAfter(Duration delay) {
            return deadlineNanos == Long.MAX_VALUE || remaining().compareTo(delay) > 0;
        }

        private static long saturatingAdd(long left, long right) {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }

        private static long timeoutNanos(Duration timeout) {
            try {
                return timeout.toNanos();
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }
    }

    private static final class Circuit {
        private int consecutiveFailures;
        private long openedAtMillis = -1;
        private boolean halfOpenProbeInFlight;

        synchronized boolean allow(long nowMillis, long openDurationMillis) {
            if (openedAtMillis < 0) {
                return true;
            }
            if (nowMillis - openedAtMillis < openDurationMillis || halfOpenProbeInFlight) {
                return false;
            }
            halfOpenProbeInFlight = true;
            return true;
        }

        synchronized RpcCircuitState state(long nowMillis, long openDurationMillis) {
            if (openedAtMillis < 0) {
                return RpcCircuitState.CLOSED;
            }
            return nowMillis - openedAtMillis < openDurationMillis
                    ? RpcCircuitState.OPEN
                    : RpcCircuitState.HALF_OPEN;
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            openedAtMillis = -1;
            halfOpenProbeInFlight = false;
        }

        synchronized boolean recordFailure(int threshold, long nowMillis, long openDurationMillis) {
            if (openedAtMillis >= 0 && nowMillis - openedAtMillis < openDurationMillis) {
                return false;
            }
            if (openedAtMillis >= 0) {
                openedAtMillis = nowMillis;
                halfOpenProbeInFlight = false;
                return true;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= threshold) {
                openedAtMillis = nowMillis;
                halfOpenProbeInFlight = false;
                return true;
            }
            return false;
        }

        synchronized void releaseProbe() {
            halfOpenProbeInFlight = false;
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
