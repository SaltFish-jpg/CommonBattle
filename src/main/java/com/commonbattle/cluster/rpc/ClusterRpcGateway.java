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
import com.commonbattle.runtime.DrainableComponent;

import java.io.Serializable;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于服务目录和跨服传输的 RPC 网关。
 * 请求按服务类型路由到目标服务；响应按 requestId 回到调用方，再由 ActorRpcClient 投递回所属 Actor 邮箱。
 */
public final class ClusterRpcGateway implements RpcGateway, AutoCloseable, DrainableComponent {
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
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final AtomicLong routeCursor = new AtomicLong();
    private final Map<Long, PendingRpcCall<?>> callbacks = new ConcurrentHashMap<>();
    private final Map<String, RpcEndpointHandler> handlers = new ConcurrentHashMap<>();
    private final List<RpcTargetSelector> targetSelectors = new CopyOnWriteArrayList<>();
    private final RpcIdempotencyStore idempotencyStore;

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
            RpcIdempotencyStore idempotencyStore
    ) {
        this(local, directory, topology, transport, bindTransport, governance,
                Executors.newSingleThreadScheduledExecutor(new RpcTimeoutThreadFactory()), true, idempotencyStore);
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

    public ClusterRpcGateway(
            ServiceDescriptor local,
            ClusterDirectory directory,
            ClusterTopology topology,
            ClusterTransport transport,
            boolean bindTransport,
            RpcGovernanceConfig governance,
            ScheduledExecutorService timeoutScheduler,
            RpcIdempotencyStore idempotencyStore
    ) {
        this(local, directory, topology, transport, bindTransport, governance, timeoutScheduler, false, idempotencyStore);
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
        this(local, directory, topology, transport, bindTransport, governance, timeoutScheduler, ownsTimeoutScheduler,
                new InMemoryRpcIdempotencyStore(governance.idempotencyCacheCapacity()));
    }

    private ClusterRpcGateway(
            ServiceDescriptor local,
            ClusterDirectory directory,
            ClusterTopology topology,
            ClusterTransport transport,
            boolean bindTransport,
            RpcGovernanceConfig governance,
            ScheduledExecutorService timeoutScheduler,
            boolean ownsTimeoutScheduler,
            RpcIdempotencyStore idempotencyStore
    ) {
        this.local = Objects.requireNonNull(local, "local");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.ownsTimeoutScheduler = ownsTimeoutScheduler;
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        if (bindTransport) {
            this.transport.bind(local, this::onMessage);
        }
    }

    public void handle(String operation, RpcEndpointHandler handler) {
        handlers.put(Objects.requireNonNull(operation, "operation"), Objects.requireNonNull(handler, "handler"));
    }

    /**
     * 注册业务专用选路器。
     * 选择器只影响其声明支持的请求，其它请求继续走网关默认路由。
     */
    public ClusterRpcGateway addTargetSelector(RpcTargetSelector selector) {
        targetSelectors.add(Objects.requireNonNull(selector, "selector"));
        return this;
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

    @Override
    public void beginDrain() {
        draining.set(true);
    }

    @Override
    public void resumeAccepting() {
        draining.set(false);
    }

    @Override
    public boolean isDraining() {
        return draining.get();
    }

    public RpcGatewayStats stats() {
        return metrics.snapshot(idempotencyStore.size());
    }

    private ServiceDescriptor resolveTarget(RpcRequest<?> request) {
        if (ServiceId.isWireName(request.target())) {
            ServiceId serviceId = ServiceId.parse(request.target());
            return directory.routable(serviceId)
                    .orElseThrow(() -> new RpcNoRoutableServiceException(serviceId.kind(), request.operation()));
        }
        ServiceKind kind = ServiceKind.valueOf(request.target());
        List<ServiceDescriptor> routable = directory.routable(kind);
        List<ServiceDescriptor> supported = routable.stream()
                .filter(service -> service.supports(request.operation()))
                .toList();
        List<ServiceDescriptor> candidates = supported.isEmpty() ? routable : supported;
        for (RpcTargetSelector selector : targetSelectors) {
            if (selector.supports(request, kind)) {
                return selector.select(request, candidates);
            }
        }
        if (!supported.isEmpty()) {
            return select(supported);
        }
        return select(kind, request.operation(), routable);
    }

    private ServiceDescriptor nextHop(ServiceDescriptor target) {
        return topology.nextHop(local.id(), target, directory.routable(ServiceKind.PROXY));
    }

    private ServiceDescriptor roundRobin(List<ServiceDescriptor> services) {
        return roundRobin(null, "", services);
    }

    private ServiceDescriptor select(List<ServiceDescriptor> services) {
        return select(null, "", services);
    }

    private ServiceDescriptor select(ServiceKind kind, String operation, List<ServiceDescriptor> services) {
        if (services.isEmpty()) {
            return roundRobin(kind, operation, services);
        }
        long bestLoad = services.stream()
                .mapToLong(ServiceDescriptor::loadScore)
                .min()
                .orElse(com.commonbattle.cluster.ServiceMetadata.UNKNOWN_LOAD_SCORE);
        List<ServiceDescriptor> candidates = services.stream()
                .filter(service -> service.loadScore() == bestLoad)
                .toList();
        return roundRobin(kind, operation, candidates);
    }

    private ServiceDescriptor roundRobin(ServiceKind kind, String operation, List<ServiceDescriptor> services) {
        if (services.isEmpty()) {
            if (kind == null) {
                throw new IllegalStateException("No routable service registered");
            }
            throw new RpcNoRoutableServiceException(kind, operation);
        }
        int index = Math.floorMod(routeCursor.getAndIncrement(), services.size());
        return services.get(index);
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
        if (draining.get()) {
            metrics.rejected();
            replyFailure(envelope, new RpcServiceDrainingException(local.id(), envelope.operation()));
            return;
        }
        RpcEndpointHandler handler = handlers.get(envelope.operation());
        if (handler == null) {
            replyFailure(envelope, new IllegalStateException("No handler for " + envelope.operation()));
            return;
        }
        RpcIdempotencyKey idempotencyKey = idempotencyKey(envelope);
        if (idempotencyKey != null) {
            RpcIdempotencyResult cached = cached(idempotencyKey);
            if (cached != null) {
                replyCached(envelope, cached);
                return;
            }
        }
        try {
            handler.handle(envelope, new RpcResponder() {
                @Override
                public void success(Object payload) {
                    cache(idempotencyKey, RpcIdempotencyResult.success(payload));
                    replySuccess(envelope, payload);
                }

                @Override
                public void failure(Throwable error) {
                    cache(idempotencyKey, RpcIdempotencyResult.failure(new RpcError(error.getMessage())));
                    replyFailure(envelope, error);
                }
            });
        } catch (RuntimeException e) {
            cache(idempotencyKey, RpcIdempotencyResult.failure(new RpcError(e.getMessage())));
            replyFailure(envelope, e);
        }
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

    private RpcIdempotencyKey idempotencyKey(ClusterEnvelope envelope) {
        String key = envelope.metadata().get(IDEMPOTENCY_KEY);
        if (key == null || key.isBlank()) {
            return null;
        }
        return new RpcIdempotencyKey(envelope.source(), envelope.operation(), key);
    }

    private RpcIdempotencyResult cached(RpcIdempotencyKey key) {
        return idempotencyStore.get(key);
    }

    private void cache(RpcIdempotencyKey key, RpcIdempotencyResult result) {
        if (key == null) {
            return;
        }
        idempotencyStore.put(key, result);
    }

    private void replyCached(ClusterEnvelope request, RpcIdempotencyResult result) {
        if (result.success()) {
            replySuccess(request, result.payload());
        } else {
            sendReply(request.source(), new ClusterEnvelope(request.requestId(), local.id(), request.source(),
                    RPC_FAILURE, result.payload()));
        }
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
