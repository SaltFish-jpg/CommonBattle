package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterTransport;

import java.io.Serializable;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于服务目录和跨服传输的 RPC 网关。
 * 请求按服务类型路由到目标服务；响应按 requestId 回到调用方，再由 ActorRpcClient 投递回所属 Actor 邮箱。
 */
public final class ClusterRpcGateway implements RpcGateway, AutoCloseable {
    private static final String RPC_SUCCESS = "$rpc.success";
    private static final String RPC_FAILURE = "$rpc.failure";
    private static final String IDEMPOTENCY_KEY = "rpc.idempotency_key";

    private final ServiceDescriptor local;
    private final ClusterDirectory directory;
    private final ClusterTopology topology;
    private final ClusterTransport transport;
    private final RpcGovernanceConfig governance;
    private final ScheduledExecutorService timeoutScheduler;
    private final boolean ownsTimeoutScheduler;
    private final RpcGatewayMetrics metrics = new RpcGatewayMetrics();
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final Map<Long, PendingRpcCall<?>> callbacks = new ConcurrentHashMap<>();
    private final Map<String, RpcEndpointHandler> handlers = new ConcurrentHashMap<>();
    private final Map<IdempotencyKey, CachedRpcResult> idempotencyCache;

    public ClusterRpcGateway(
            ServiceDescriptor local,
            ClusterDirectory directory,
            ClusterTopology topology,
            ClusterTransport transport
    ) {
        this(local, directory, topology, transport, true);
    }

    public ClusterRpcGateway(
            ServiceDescriptor local,
            ClusterDirectory directory,
            ClusterTopology topology,
            ClusterTransport transport,
            boolean bindTransport
    ) {
        this(local, directory, topology, transport, bindTransport, RpcGovernanceConfig.defaults());
    }

    public ClusterRpcGateway(
            ServiceDescriptor local,
            ClusterDirectory directory,
            ClusterTopology topology,
            ClusterTransport transport,
            boolean bindTransport,
            RpcGovernanceConfig governance
    ) {
        this(local, directory, topology, transport, bindTransport, governance,
                Executors.newSingleThreadScheduledExecutor(new RpcTimeoutThreadFactory()), true);
    }

    public ClusterRpcGateway(
            ServiceDescriptor local,
            ClusterDirectory directory,
            ClusterTopology topology,
            ClusterTransport transport,
            boolean bindTransport,
            RpcGovernanceConfig governance,
            ScheduledExecutorService timeoutScheduler
    ) {
        this(local, directory, topology, transport, bindTransport, governance, timeoutScheduler, false);
    }

    private ClusterRpcGateway(
            ServiceDescriptor local,
            ClusterDirectory directory,
            ClusterTopology topology,
            ClusterTransport transport,
            boolean bindTransport,
            RpcGovernanceConfig governance,
            ScheduledExecutorService timeoutScheduler,
            boolean ownsTimeoutScheduler
    ) {
        this.local = Objects.requireNonNull(local, "local");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.ownsTimeoutScheduler = ownsTimeoutScheduler;
        this.idempotencyCache = createIdempotencyCache(governance.idempotencyCacheCapacity());
        if (bindTransport) {
            this.transport.bind(local, this::onMessage);
        }
    }

    public void handle(String operation, RpcEndpointHandler handler) {
        handlers.put(Objects.requireNonNull(operation, "operation"), Objects.requireNonNull(handler, "handler"));
    }

    @Override
    public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        call(request, callback, RpcCallOptions.of(governance.defaultTimeout()));
    }

    public <T> void call(RpcRequest<T> request, RpcCallback<T> callback, RpcCallOptions options) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(options, "options");
        if (callbacks.size() >= governance.maxPendingRequests()) {
            metrics.rejected();
            callback.failure(new RpcRejectedException(governance.maxPendingRequests()));
            return;
        }
        long requestId = nextRequestId.getAndIncrement();
        PendingRpcCall<T> pending = new PendingRpcCall<>(request, callback, System.nanoTime(), options.timeout());
        callbacks.put(requestId, pending);
        metrics.sent();
        pending.timeoutFuture = timeoutScheduler.schedule(
                () -> timeout(requestId, pending),
                options.timeout().toMillis(),
                TimeUnit.MILLISECONDS
        );
        try {
            ServiceDescriptor target = resolveTarget(request);
            ClusterEnvelope envelope = new ClusterEnvelope(
                    requestId,
                    local.id(),
                    target.id(),
                    request.operation(),
                    request.payload(),
                    metadata(options)
            );
            transport.send(nextHop(target).id(), envelope);
        } catch (RuntimeException e) {
            if (callbacks.remove(requestId, pending)) {
                pending.cancelTimeout();
                metrics.failure();
                callback.failure(e);
            }
        }
    }

    public RpcGatewayStats stats() {
        synchronized (idempotencyCache) {
            return metrics.snapshot(idempotencyCache.size());
        }
    }

    private ServiceDescriptor resolveTarget(RpcRequest<?> request) {
        ServiceKind kind = ServiceKind.valueOf(request.target());
        return directory.list(kind).stream()
                .filter(service -> service.supports(request.operation()))
                .findFirst()
                .orElseGet(() -> directory.first(kind));
    }

    private ServiceDescriptor nextHop(ServiceDescriptor target) {
        return topology.nextHop(local.id(), target, directory.list(ServiceKind.PROXY));
    }

    public void onMessage(ClusterEnvelope envelope) {
        if (RPC_SUCCESS.equals(envelope.operation())) {
            completeSuccess(envelope);
            return;
        }
        if (RPC_FAILURE.equals(envelope.operation())) {
            completeFailure(envelope);
            return;
        }
        RpcEndpointHandler handler = handlers.get(envelope.operation());
        if (handler == null) {
            replyFailure(envelope, new IllegalStateException("No handler for " + envelope.operation()));
            return;
        }
        IdempotencyKey idempotencyKey = idempotencyKey(envelope);
        if (idempotencyKey != null) {
            CachedRpcResult cached = cached(idempotencyKey);
            if (cached != null) {
                replyCached(envelope, cached);
                return;
            }
        }
        handler.handle(envelope, new RpcResponder() {
            @Override
            public void success(Object payload) {
                cache(idempotencyKey, CachedRpcResult.success(payload));
                replySuccess(envelope, payload);
            }

            @Override
            public void failure(Throwable error) {
                cache(idempotencyKey, CachedRpcResult.failure(new RpcError(error.getMessage())));
                replyFailure(envelope, error);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> void completeSuccess(ClusterEnvelope envelope) {
        PendingRpcCall<T> pending = (PendingRpcCall<T>) callbacks.remove(envelope.requestId());
        if (pending != null) {
            pending.cancelTimeout();
            recordCompleted(pending, true);
            pending.callback.success((T) envelope.payload());
        }
    }

    private void completeFailure(ClusterEnvelope envelope) {
        PendingRpcCall<?> pending = callbacks.remove(envelope.requestId());
        if (pending != null) {
            pending.cancelTimeout();
            recordCompleted(pending, false);
            pending.callback.failure(new IllegalStateException(String.valueOf(envelope.payload())));
        }
    }

    private void replySuccess(ClusterEnvelope request, Object payload) {
        ClusterEnvelope response = new ClusterEnvelope(request.requestId(), local.id(), request.source(), RPC_SUCCESS, payload);
        sendReply(request.source(), response);
    }

    private void replyFailure(ClusterEnvelope request, Throwable error) {
        ClusterEnvelope response = new ClusterEnvelope(
                request.requestId(),
                local.id(),
                request.source(),
                RPC_FAILURE,
                new RpcError(error.getMessage())
        );
        sendReply(request.source(), response);
    }

    private void sendReply(ServiceId target, ClusterEnvelope response) {
        ServiceDescriptor descriptor = new ServiceDescriptor(target, local.endpoint(), Set.of(), java.util.Map.of());
        transport.send(topology.nextHop(local.id(), descriptor, directory.list(ServiceKind.PROXY)).id(), response);
    }

    private <T> void timeout(long requestId, PendingRpcCall<T> pending) {
        if (callbacks.remove(requestId, pending)) {
            metrics.timeout();
            pending.callback.failure(new RpcTimeoutException(pending.request, pending.timeout));
        }
    }

    private void recordCompleted(PendingRpcCall<?> pending, boolean success) {
        if (success) {
            metrics.success();
        } else {
            metrics.failure();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - pending.startedAtNanos);
        if (elapsed.compareTo(governance.slowCallThreshold()) > 0) {
            metrics.slow();
        }
    }

    private static Map<String, String> metadata(RpcCallOptions options) {
        if (options.idempotencyKey().isBlank()) {
            return Map.of();
        }
        return Map.of(IDEMPOTENCY_KEY, options.idempotencyKey());
    }

    private IdempotencyKey idempotencyKey(ClusterEnvelope envelope) {
        String key = envelope.metadata().get(IDEMPOTENCY_KEY);
        if (key == null || key.isBlank() || governance.idempotencyCacheCapacity() == 0) {
            return null;
        }
        return new IdempotencyKey(envelope.source(), envelope.operation(), key);
    }

    private CachedRpcResult cached(IdempotencyKey key) {
        synchronized (idempotencyCache) {
            return idempotencyCache.get(key);
        }
    }

    private void cache(IdempotencyKey key, CachedRpcResult result) {
        if (key == null) {
            return;
        }
        synchronized (idempotencyCache) {
            idempotencyCache.put(key, result);
        }
    }

    private void replyCached(ClusterEnvelope request, CachedRpcResult result) {
        if (result.success()) {
            replySuccess(request, result.payload());
        } else {
            sendReply(request.source(), new ClusterEnvelope(request.requestId(), local.id(), request.source(),
                    RPC_FAILURE, result.payload()));
        }
    }

    private static Map<IdempotencyKey, CachedRpcResult> createIdempotencyCache(int capacity) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<IdempotencyKey, CachedRpcResult> eldest) {
                return size() > capacity;
            }
        });
    }

    @Override
    public void close() {
        if (ownsTimeoutScheduler) {
            timeoutScheduler.shutdownNow();
        }
    }

    private static final class PendingRpcCall<T> {
        private final RpcRequest<T> request;
        private final RpcCallback<T> callback;
        private final long startedAtNanos;
        private final Duration timeout;
        private volatile ScheduledFuture<?> timeoutFuture;

        private PendingRpcCall(RpcRequest<T> request, RpcCallback<T> callback, long startedAtNanos, Duration timeout) {
            this.request = request;
            this.callback = callback;
            this.startedAtNanos = startedAtNanos;
            this.timeout = timeout;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private record IdempotencyKey(ServiceId source, String operation, String key) {
    }

    private record CachedRpcResult(boolean success, Object payload) {
        static CachedRpcResult success(Object payload) {
            return new CachedRpcResult(true, payload);
        }

        static CachedRpcResult failure(Object payload) {
            return new CachedRpcResult(false, payload);
        }
    }

    private static final class RpcTimeoutThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "common-battle-rpc-timeout");
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * 跨服 RPC 失败响应的可序列化载体。
     */
    public record RpcError(String message) implements Serializable {
        @Override
        public String toString() {
            return message;
        }
    }
}
