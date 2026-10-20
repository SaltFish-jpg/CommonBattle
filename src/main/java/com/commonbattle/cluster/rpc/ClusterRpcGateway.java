package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.cluster.network.ClusterDeliveryProtocol;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterSendCallback;
import com.commonbattle.cluster.network.ClusterTransport;
import com.commonbattle.runtime.DrainableComponent;

import java.io.Serializable;
import java.time.Duration;
import java.time.Clock;
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
import java.util.function.Consumer;

/**
 * 基于服务目录和跨服传输的 RPC 网关。
 * 请求按服务类型路由到目标服务；响应按 requestId 回到调用方，再由 ActorRpcClient 投递回所属 Actor 邮箱。
 */
public final class ClusterRpcGateway implements OptionedRpcGateway, AutoCloseable, DrainableComponent {
    private static final String RPC_SUCCESS = "$rpc.success";
    private static final String RPC_FAILURE = "$rpc.failure";
    private static final String IDEMPOTENCY_KEY = "rpc.idempotency_key";
    private static final String RPC_TIMEOUT_MILLIS = "rpc.timeout_millis";
    private static final long DELIVERY_ACK_TIMEOUT_MILLIS = 1_000;

    private final ServiceDescriptor local;
    private final ClusterDirectory directory;
    private final ClusterTopology topology;
    private final ClusterTransport transport;
    private final RpcGovernanceConfig governance;
    private final ScheduledExecutorService timeoutScheduler;
    private final boolean ownsTimeoutScheduler;
    private final RpcGatewayMetrics metrics = new RpcGatewayMetrics();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final AtomicLong routeCursor = new AtomicLong();
    private final AtomicLong proxyRouteCursor = new AtomicLong();
    private final AtomicLong nextDeliveryId = new AtomicLong();
    private final AtomicLong nextIdempotencyOwnerId = new AtomicLong();
    private final Map<Long, PendingRpcCall<?>> callbacks = new ConcurrentHashMap<>();
    private final Map<String, PendingDeliveryAck> deliveryAcks = new ConcurrentHashMap<>();
    private final Map<String, RpcEndpointHandler> handlers = new ConcurrentHashMap<>();
    private final Map<RpcIdempotencyKey, InFlightIdempotentCall> inFlightIdempotentCalls =
            new ConcurrentHashMap<>();
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
                new InMemoryRpcIdempotencyStore(
                        governance.idempotencyCacheCapacity(), governance.idempotencyCacheTtl(), Clock.systemUTC()
                ));
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
        if (closed.get()) {
            metrics.rejected();
            callback.failure(new RpcGatewayClosedException(local.id()));
            return;
        }
        if (callbacks.size() >= governance.maxPendingRequests()) {
            metrics.rejected();
            callback.failure(new RpcRejectedException(governance.maxPendingRequests()));
            return;
        }
        long requestId = nextRequestId.getAndIncrement();
        PendingRpcCall<T> pending = new PendingRpcCall<>(request, callback, System.nanoTime(), options.timeout());
        callbacks.put(requestId, pending);
        metrics.sent();
        if (closed.get()) {
            failPending(requestId, pending, new RpcGatewayClosedException(local.id()));
            return;
        }
        try {
            pending.timeoutFuture = timeoutScheduler.schedule(
                    () -> timeout(requestId, pending),
                    options.timeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            ServiceDescriptor target = resolveTarget(request, options);
            ClusterEnvelope envelope = new ClusterEnvelope(
                    requestId,
                    local.id(),
                    target.id(),
                    request.operation(),
                    request.payload(),
                    metadata(options)
            );
            ServiceDescriptor nextHop = pending.route(envelope, orderedRequestHops(target));
            sendRequest(requestId, pending, nextHop);
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
        return metrics.snapshot(idempotencyStore.size(), deliveryAcks.size(), inFlightIdempotentCalls.size());
    }

    public RpcCallOptions defaultCallOptions() {
        return RpcCallOptions.of(governance.defaultTimeout());
    }

    private ServiceDescriptor resolveTarget(RpcRequest<?> request, RpcCallOptions options) {
        if (ServiceId.isWireName(request.target())) {
            ServiceId serviceId = ServiceId.parse(request.target());
            return directory.routable(serviceId)
                    .filter(service -> ServiceMetadata.matches(service, options.requiredTargetMetadata()))
                    .orElseThrow(() -> new RpcNoRoutableServiceException(serviceId.kind(), request.operation()));
        }
        ServiceKind kind = ServiceKind.valueOf(request.target());
        List<ServiceDescriptor> routable = servicesMatching(directory.routable(kind), options.requiredTargetMetadata());
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

    private static List<ServiceDescriptor> servicesMatching(
            List<ServiceDescriptor> services,
            Map<String, String> requiredMetadata
    ) {
        if (requiredMetadata.isEmpty()) {
            return services;
        }
        return services.stream()
                .filter(service -> ServiceMetadata.matches(service, requiredMetadata))
                .toList();
    }

    private List<ServiceDescriptor> orderedRequestHops(ServiceDescriptor target) {
        List<ServiceDescriptor> nextHops = topology.nextHops(
                local.id(), target, directory.routable(ServiceKind.PROXY)
        );
        if (nextHops.size() < 2) {
            return nextHops;
        }
        int start = Math.floorMod(proxyRouteCursor.getAndIncrement(), nextHops.size());
        return java.util.stream.IntStream.range(0, nextHops.size())
                .mapToObj(offset -> nextHops.get((start + offset) % nextHops.size()))
                .toList();
    }

    private void sendRequest(long requestId, PendingRpcCall<?> pending, ServiceDescriptor nextHop) {
        if (callbacks.get(requestId) != pending) {
            return;
        }
        sendWithDeliveryAck(
                nextHop.id(),
                pending.envelope(),
                () -> {
                },
                error -> handleDeliveryFailure(requestId, pending, nextHop.id(), error)
        );
    }

    private void handleDeliveryFailure(
            long requestId,
            PendingRpcCall<?> pending,
            ServiceId failedHop,
            Throwable error
    ) {
        if (callbacks.get(requestId) != pending) {
            return;
        }
        DeliveryRetry retry = pending.advanceAfter(failedHop);
        if (!retry.matched()) {
            return;
        }
        if (retry.nextHop() == null) {
            failPending(requestId, pending, error);
            return;
        }
        metrics.requestFailover();
        sendRequest(requestId, pending, retry.nextHop());
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
        if (ClusterDeliveryProtocol.isAcknowledgment(envelope)) {
            completeDeliveryAck(envelope);
            return;
        }
        acknowledgeDelivery(envelope);
        if (ClusterDeliveryProtocol.isFailure(envelope)) {
            completeDeliveryFailure(envelope);
            return;
        }
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
        InFlightIdempotentCall idempotentCall = null;
        if (idempotencyKey != null) {
            RpcIdempotencyResult cached = cached(idempotencyKey);
            if (cached != null) {
                replyCached(envelope, cached);
                return;
            }
            InFlightIdempotentCall created = new InFlightIdempotentCall(envelope, idempotencyDeadlineNanos(envelope));
            InFlightIdempotentCall existing = inFlightIdempotentCalls.putIfAbsent(idempotencyKey, created);
            if (existing != null) {
                metrics.idempotencyJoined();
                RpcIdempotencyResult completed = existing.join(envelope);
                if (completed != null) {
                    replyCached(envelope, completed);
                }
                return;
            }
            RpcIdempotencyResult completedBeforeClaim = cached(idempotencyKey);
            if (completedBeforeClaim != null) {
                List<ClusterEnvelope> requests = created.complete(completedBeforeClaim);
                inFlightIdempotentCalls.remove(idempotencyKey, created);
                requests.forEach(waiting -> replyCached(waiting, completedBeforeClaim));
                return;
            }
            idempotentCall = created;
        }
        CoordinatedRpcIdempotencyStore coordinator = coordinatedStore();
        if (idempotencyKey != null && coordinator != null) {
            String ownerToken = nextIdempotencyOwnerToken(envelope);
            RpcIdempotencyClaim claim;
            try {
                claim = coordinator.claim(idempotencyKey, ownerToken);
            } catch (RuntimeException error) {
                finishInbound(idempotencyKey, idempotentCall, coordinationCommitFailure());
                return;
            }
            if (claim.status() == RpcIdempotencyClaim.Status.COMPLETED) {
                finishInbound(idempotencyKey, idempotentCall, claim.result());
                return;
            }
            if (claim.status() == RpcIdempotencyClaim.Status.IN_PROGRESS) {
                awaitCoordinatedResult(envelope, handler, idempotencyKey, idempotentCall, coordinator, ownerToken);
                return;
            }
            executeInboundHandler(envelope, handler, idempotencyKey, idempotentCall, coordinator, ownerToken);
            return;
        }
        executeInboundHandler(envelope, handler, idempotencyKey, idempotentCall, null, null);
    }

    private void executeInboundHandler(
            ClusterEnvelope envelope,
            RpcEndpointHandler handler,
            RpcIdempotencyKey idempotencyKey,
            InFlightIdempotentCall idempotentCall,
            CoordinatedRpcIdempotencyStore coordinator,
            String ownerToken
    ) {
        if (coordinator != null) {
            startLeaseRenewal(idempotencyKey, idempotentCall, coordinator, ownerToken);
        }
        InFlightIdempotentCall ownedCall = idempotentCall;
        AtomicBoolean responded = new AtomicBoolean();
        try {
            handler.handle(envelope, new RpcResponder() {
                @Override
                public void success(Object payload) {
                    if (responded.compareAndSet(false, true)) {
                        completeInbound(envelope, idempotencyKey, ownedCall,
                                RpcIdempotencyResult.success(payload), true, coordinator, ownerToken);
                    }
                }

                @Override
                public void failure(Throwable error) {
                    if (responded.compareAndSet(false, true)) {
                        completeInbound(envelope, idempotencyKey, ownedCall,
                                RpcIdempotencyResult.failure(RpcError.from(error)), cacheableFailure(error),
                                coordinator, ownerToken);
                    }
                }
            });
        } catch (RuntimeException e) {
            if (responded.compareAndSet(false, true)) {
                completeInbound(envelope, idempotencyKey, ownedCall,
                        RpcIdempotencyResult.failure(RpcError.from(e)), cacheableFailure(e), coordinator, ownerToken);
            }
        }
    }

    private void awaitCoordinatedResult(
            ClusterEnvelope envelope,
            RpcEndpointHandler handler,
            RpcIdempotencyKey key,
            InFlightIdempotentCall call,
            CoordinatedRpcIdempotencyStore coordinator,
            String ownerToken
    ) {
        if (call.isCompleted()) {
            return;
        }
        long delayMillis = governance.idempotencyPollInterval().toMillis();
        ScheduledFuture<?> future = timeoutScheduler.schedule(() -> {
            if (call.isCompleted()) {
                return;
            }
            if (call.deadlineExceeded()) {
                finishInbound(key, call, coordinationPendingFailure());
                return;
            }
            try {
                RpcIdempotencyClaim claim = coordinator.claim(key, ownerToken);
                if (claim.status() == RpcIdempotencyClaim.Status.COMPLETED) {
                    finishInbound(key, call, claim.result());
                } else if (claim.status() == RpcIdempotencyClaim.Status.ACQUIRED) {
                    executeInboundHandler(envelope, handler, key, call, coordinator, ownerToken);
                } else {
                    awaitCoordinatedResult(envelope, handler, key, call, coordinator, ownerToken);
                }
            } catch (RuntimeException ignored) {
                awaitCoordinatedResult(envelope, handler, key, call, coordinator, ownerToken);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
        call.attachCoordinationFuture(future);
    }

    private void startLeaseRenewal(
            RpcIdempotencyKey key,
            InFlightIdempotentCall call,
            CoordinatedRpcIdempotencyStore coordinator,
            String ownerToken
    ) {
        long renewalMillis = Math.max(1, coordinator.leaseDuration().toMillis() / 3);
        ScheduledFuture<?> future = timeoutScheduler.scheduleWithFixedDelay(() -> {
            if (call.isCompleted()) {
                return;
            }
            try {
                if (!coordinator.renew(key, ownerToken)) {
                    finishInbound(key, call, ownershipLostFailure());
                }
            } catch (RuntimeException ignored) {
                // 短暂存储异常留给后续续租重试，租约状态仍由共享存储裁决。
            }
        }, renewalMillis, renewalMillis, TimeUnit.MILLISECONDS);
        call.attachCoordinationFuture(future);
    }

    private void completeInbound(
            ClusterEnvelope request,
            RpcIdempotencyKey key,
            InFlightIdempotentCall call,
            RpcIdempotencyResult result,
            boolean cacheResult,
            CoordinatedRpcIdempotencyStore coordinator,
            String ownerToken
    ) {
        if (key == null) {
            replyCached(request, result);
            return;
        }
        if (call.isCompleted()) {
            return;
        }
        RpcIdempotencyResult replyResult = result;
        if (coordinator != null) {
            try {
                if (cacheResult) {
                    if (!coordinator.complete(key, ownerToken, result)) {
                        replyResult = ownershipLostFailure();
                    }
                } else {
                    coordinator.release(key, ownerToken);
                }
            } catch (RuntimeException error) {
                replyResult = coordinationCommitFailure();
            }
        } else if (cacheResult) {
            cache(key, result);
        }
        finishInbound(key, call, replyResult);
    }

    private void finishInbound(
            RpcIdempotencyKey key,
            InFlightIdempotentCall call,
            RpcIdempotencyResult result
    ) {
        List<ClusterEnvelope> requests = call.complete(result);
        inFlightIdempotentCalls.remove(key, call);
        requests.forEach(waiting -> replyCached(waiting, result));
    }

    private RpcIdempotencyResult coordinationPendingFailure() {
        return RpcIdempotencyResult.failure(new RpcError(
                "rpc_idempotency_in_progress",
                "RPC idempotent execution is still in progress",
                governance.idempotencyPollInterval().toMillis()
        ));
    }

    private RpcIdempotencyResult ownershipLostFailure() {
        return RpcIdempotencyResult.failure(new RpcError(
                "rpc_idempotency_ownership_lost",
                "RPC idempotent execution ownership was lost",
                governance.idempotencyPollInterval().toMillis()
        ));
    }

    private RpcIdempotencyResult coordinationCommitFailure() {
        return RpcIdempotencyResult.failure(new RpcError(
                "rpc_idempotency_commit_failed",
                "RPC idempotent result could not be committed",
                governance.idempotencyPollInterval().toMillis()
        ));
    }

    private static boolean cacheableFailure(Throwable error) {
        return error instanceof RpcStructuredException structured && structured.retryAfter().isZero();
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
            pending.callback.failure(remoteFailure(envelope.payload()));
        }
    }

    private void acknowledgeDelivery(ClusterEnvelope envelope) {
        ClusterEnvelope acknowledgment = ClusterDeliveryProtocol.acknowledgment(local.id(), envelope);
        if (acknowledgment == null) {
            return;
        }
        transport.send(acknowledgment.target(), acknowledgment, ClusterSendCallback.NOOP);
    }

    private void completeDeliveryFailure(ClusterEnvelope envelope) {
        ClusterForwardingException failure = forwardingFailure(envelope);
        metrics.forwardingFailure();
        if (failDeliveryAck(envelope, failure)) {
            return;
        }
        PendingRpcCall<?> pending = callbacks.get(envelope.requestId());
        if (pending == null) {
            return;
        }
        handleDeliveryFailure(
                envelope.requestId(), pending, envelope.source(), failure
        );
    }

    private boolean failDeliveryAck(ClusterEnvelope failure, Throwable error) {
        String deliveryId = failure.metadata().get(ClusterDeliveryProtocol.DELIVERY_ID);
        if (deliveryId == null || deliveryId.isBlank()) {
            return false;
        }
        PendingDeliveryAck pending = deliveryAcks.get(deliveryId);
        if (pending == null || !pending.nextHop.equals(failure.source())) {
            return false;
        }
        if (!settleDeliveryAck(deliveryId, pending)) {
            return false;
        }
        pending.onFailure.accept(error);
        return true;
    }

    private void replySuccess(ClusterEnvelope request, Object payload) {
        ClusterEnvelope response = new ClusterEnvelope(
                request.requestId(), local.id(), request.source(), RPC_SUCCESS, payload, replyMetadata(request)
        );
        sendReply(request, response);
    }

    private void replyFailure(ClusterEnvelope request, Throwable error) {
        ClusterEnvelope response = new ClusterEnvelope(
                request.requestId(),
                local.id(),
                request.source(),
                RPC_FAILURE,
                RpcError.from(error),
                replyMetadata(request)
        );
        sendReply(request, response);
    }

    private static Throwable remoteFailure(Object payload) {
        if (payload instanceof RpcError error) {
            return RpcStructuredException.from(error);
        }
        return new IllegalStateException(String.valueOf(payload));
    }

    private void sendReply(ClusterEnvelope request, ClusterEnvelope response) {
        ServiceDescriptor target = new ServiceDescriptor(
                response.target(), local.endpoint(), Set.of(), java.util.Map.of()
        );
        List<ServiceDescriptor> candidates;
        try {
            candidates = orderedReplyHops(request, target);
        } catch (RuntimeException error) {
            metrics.undeliverableReply();
            return;
        }
        sendReply(response, candidates, 0);
    }

    private List<ServiceDescriptor> orderedReplyHops(ClusterEnvelope request, ServiceDescriptor target) {
        List<ServiceDescriptor> candidates = topology.nextHops(
                local.id(), target, directory.routable(ServiceKind.PROXY)
        );
        ServiceId preferred = ClusterDeliveryProtocol.returnProxy(request);
        if (preferred == null || candidates.size() < 2 || candidates.getFirst().id().equals(target.id())) {
            return candidates;
        }
        return java.util.stream.Stream.concat(
                        candidates.stream().filter(candidate -> candidate.id().equals(preferred)),
                        candidates.stream().filter(candidate -> !candidate.id().equals(preferred))
                )
                .toList();
    }

    private void sendReply(ClusterEnvelope response, List<ServiceDescriptor> candidates, int index) {
        ServiceDescriptor nextHop = candidates.get(index);
        sendWithDeliveryAck(
                nextHop.id(),
                response,
                () -> {
                },
                error -> {
                metrics.replyWriteFailure();
                int nextIndex = index + 1;
                if (nextIndex >= candidates.size()) {
                    metrics.undeliverableReply();
                    return;
                }
                metrics.replyFailover();
                sendReply(response, candidates, nextIndex);
                }
        );
    }

    private void sendWithDeliveryAck(
            ServiceId nextHop,
            ClusterEnvelope envelope,
            Runnable onAcknowledged,
            Consumer<Throwable> onFailure
    ) {
        if (closed.get()) {
            onFailure.accept(new RpcGatewayClosedException(local.id()));
            return;
        }
        if (nextHop.equals(envelope.target())) {
            transport.send(nextHop, envelope, new ClusterSendCallback() {
                @Override
                public void success() {
                    onAcknowledged.run();
                }

                @Override
                public void failure(Throwable error) {
                    onFailure.accept(error);
                }
            });
            return;
        }
        String deliveryId = local.id().wireName() + ':' + nextDeliveryId.incrementAndGet();
        ClusterEnvelope delivered = ClusterDeliveryProtocol.forHop(
                envelope, local.id(), deliveryId
        );
        PendingDeliveryAck pending = new PendingDeliveryAck(nextHop, onAcknowledged, onFailure);
        deliveryAcks.put(deliveryId, pending);
        try {
            pending.timeoutFuture = timeoutScheduler.schedule(
                    () -> deliveryAckTimedOut(deliveryId, pending),
                    DELIVERY_ACK_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException error) {
            settleDeliveryAck(deliveryId, pending);
            onFailure.accept(error);
            return;
        }
        transport.send(nextHop, delivered, new ClusterSendCallback() {
            @Override
            public void success() {
            }

            @Override
            public void failure(Throwable error) {
                if (settleDeliveryAck(deliveryId, pending)) {
                    metrics.deliveryAckWriteFailure();
                    onFailure.accept(error);
                }
            }
        });
    }

    private void completeDeliveryAck(ClusterEnvelope acknowledgment) {
        String deliveryId = acknowledgment.metadata().get(ClusterDeliveryProtocol.DELIVERY_ID);
        if (deliveryId == null || deliveryId.isBlank()) {
            return;
        }
        PendingDeliveryAck pending = deliveryAcks.get(deliveryId);
        if (pending == null || !pending.nextHop.equals(acknowledgment.source())) {
            return;
        }
        if (settleDeliveryAck(deliveryId, pending)) {
            pending.onAcknowledged.run();
        }
    }

    private void deliveryAckTimedOut(String deliveryId, PendingDeliveryAck pending) {
        if (!settleDeliveryAck(deliveryId, pending)) {
            return;
        }
        metrics.deliveryAckTimeout();
        pending.onFailure.accept(new com.commonbattle.cluster.network.ClusterTransportException(
                pending.nextHop,
                "Delivery ACK timed out for " + pending.nextHop.wireName()
        ));
    }

    private boolean settleDeliveryAck(String deliveryId, PendingDeliveryAck pending) {
        if (!deliveryAcks.remove(deliveryId, pending)) {
            return false;
        }
        pending.cancelTimeout();
        return true;
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
        return Map.of(
                IDEMPOTENCY_KEY, options.idempotencyKey(),
                RPC_TIMEOUT_MILLIS, Long.toString(options.timeout().toMillis())
        );
    }

    private static Map<String, String> replyMetadata(ClusterEnvelope request) {
        ServiceId returnProxy = ClusterDeliveryProtocol.returnProxy(request);
        if (returnProxy == null) {
            return Map.of();
        }
        return Map.of(ClusterDeliveryProtocol.RETURN_PROXY, returnProxy.wireName());
    }

    private static ClusterForwardingException forwardingFailure(ClusterEnvelope envelope) {
        String target = envelope.metadata().get(ClusterDeliveryProtocol.FAILED_TARGET);
        String operation = envelope.metadata().getOrDefault(
                ClusterDeliveryProtocol.FAILED_OPERATION,
                "unknown"
        );
        ServiceId failedTarget;
        try {
            failedTarget = target == null ? envelope.target() : ServiceId.parse(target);
        } catch (RuntimeException ignored) {
            failedTarget = envelope.target();
        }
        return new ClusterForwardingException(
                envelope.source(), failedTarget, operation, String.valueOf(envelope.payload())
        );
    }

    private RpcIdempotencyKey idempotencyKey(ClusterEnvelope envelope) {
        String key = envelope.metadata().get(IDEMPOTENCY_KEY);
        if (key == null || key.isBlank()) {
            return null;
        }
        return new RpcIdempotencyKey(envelope.source(), envelope.operation(), key);
    }

    private long idempotencyDeadlineNanos(ClusterEnvelope envelope) {
        long timeoutMillis = governance.defaultTimeout().toMillis();
        String configured = envelope.metadata().get(RPC_TIMEOUT_MILLIS);
        if (configured != null) {
            try {
                timeoutMillis = Math.max(1, Long.parseLong(configured));
            } catch (NumberFormatException ignored) {
            }
        }
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long now = System.nanoTime();
        return timeoutNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private String nextIdempotencyOwnerToken(ClusterEnvelope envelope) {
        return local.id().wireName() + ':' + envelope.requestId() + ':'
                + nextIdempotencyOwnerId.incrementAndGet();
    }

    private CoordinatedRpcIdempotencyStore coordinatedStore() {
        return idempotencyStore instanceof CoordinatedRpcIdempotencyStore coordinated ? coordinated : null;
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
            sendReply(request, new ClusterEnvelope(
                    request.requestId(), local.id(), request.source(), RPC_FAILURE,
                    result.payload(), replyMetadata(request)
            ));
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        draining.set(true);
        RpcGatewayClosedException failure = new RpcGatewayClosedException(local.id());
        callbacks.forEach((requestId, pending) -> failPending(requestId, pending, failure));
        deliveryAcks.forEach((deliveryId, pending) -> settleDeliveryAck(deliveryId, pending));
        inFlightIdempotentCalls.values().forEach(InFlightIdempotentCall::cancelCoordination);
        if (ownsTimeoutScheduler) {
            timeoutScheduler.shutdownNow();
        }
    }

    private void failPending(long requestId, PendingRpcCall<?> pending, Throwable failure) {
        if (!callbacks.remove(requestId, pending)) {
            return;
        }
        pending.cancelTimeout();
        metrics.failure();
        try {
            pending.callback.failure(failure);
        } catch (Throwable ignored) {
        }
    }

    private static final class PendingRpcCall<T> {
        private final RpcRequest<T> request;
        private final RpcCallback<T> callback;
        private final long startedAtNanos;
        private final Duration timeout;
        private volatile ScheduledFuture<?> timeoutFuture;
        private ClusterEnvelope envelope;
        private List<ServiceDescriptor> nextHops = List.of();
        private int nextHopIndex = -1;

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

        private synchronized ServiceDescriptor route(
                ClusterEnvelope envelope,
                List<ServiceDescriptor> nextHops
        ) {
            this.envelope = Objects.requireNonNull(envelope, "envelope");
            this.nextHops = List.copyOf(Objects.requireNonNull(nextHops, "nextHops"));
            if (this.nextHops.isEmpty()) {
                throw new IllegalArgumentException("RPC route must contain at least one next hop");
            }
            this.nextHopIndex = 0;
            return this.nextHops.getFirst();
        }

        private synchronized ClusterEnvelope envelope() {
            return Objects.requireNonNull(envelope, "RPC route is not initialized");
        }

        private synchronized DeliveryRetry advanceAfter(ServiceId failedHop) {
            if (nextHopIndex < 0 || !nextHops.get(nextHopIndex).id().equals(failedHop)) {
                return DeliveryRetry.stale();
            }
            if (nextHopIndex + 1 >= nextHops.size()) {
                return DeliveryRetry.exhausted();
            }
            nextHopIndex++;
            return DeliveryRetry.retry(nextHops.get(nextHopIndex));
        }
    }

    private record DeliveryRetry(boolean matched, ServiceDescriptor nextHop) {
        private static DeliveryRetry stale() {
            return new DeliveryRetry(false, null);
        }

        private static DeliveryRetry exhausted() {
            return new DeliveryRetry(true, null);
        }

        private static DeliveryRetry retry(ServiceDescriptor nextHop) {
            return new DeliveryRetry(true, nextHop);
        }
    }

    private static final class PendingDeliveryAck {
        private final ServiceId nextHop;
        private final Runnable onAcknowledged;
        private final Consumer<Throwable> onFailure;
        private volatile ScheduledFuture<?> timeoutFuture;

        private PendingDeliveryAck(
                ServiceId nextHop,
                Runnable onAcknowledged,
                Consumer<Throwable> onFailure
        ) {
            this.nextHop = nextHop;
            this.onAcknowledged = onAcknowledged;
            this.onFailure = onFailure;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private static final class InFlightIdempotentCall {
        private final List<ClusterEnvelope> requests = new java.util.ArrayList<>();
        private final long deadlineNanos;
        private RpcIdempotencyResult completed;
        private ScheduledFuture<?> coordinationFuture;

        private InFlightIdempotentCall(ClusterEnvelope owner, long deadlineNanos) {
            requests.add(owner);
            this.deadlineNanos = deadlineNanos;
        }

        private synchronized RpcIdempotencyResult join(ClusterEnvelope request) {
            if (completed != null) {
                return completed;
            }
            requests.add(request);
            return null;
        }

        private synchronized List<ClusterEnvelope> complete(RpcIdempotencyResult result) {
            if (completed != null) {
                return List.of();
            }
            completed = Objects.requireNonNull(result, "result");
            cancelCoordination();
            return List.copyOf(requests);
        }

        private synchronized void attachCoordinationFuture(ScheduledFuture<?> future) {
            Objects.requireNonNull(future, "future");
            if (completed != null) {
                future.cancel(false);
                return;
            }
            ScheduledFuture<?> previous = coordinationFuture;
            coordinationFuture = future;
            if (previous != null && previous != future) {
                previous.cancel(false);
            }
        }

        private synchronized boolean isCompleted() {
            return completed != null;
        }

        private boolean deadlineExceeded() {
            return System.nanoTime() >= deadlineNanos;
        }

        private synchronized void cancelCoordination() {
            ScheduledFuture<?> future = coordinationFuture;
            coordinationFuture = null;
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
    public record RpcError(String code, String message, long retryAfterMillis) implements Serializable {
        public RpcError {
            code = code == null || code.isBlank() ? RpcStructuredException.REMOTE_ERROR : code;
            message = message == null ? "" : message;
            retryAfterMillis = Math.max(0, retryAfterMillis);
        }

        public RpcError(String message) {
            this(RpcStructuredException.REMOTE_ERROR, message, 0);
        }

        public static RpcError from(Throwable error) {
            if (error instanceof RpcStructuredException structured) {
                return new RpcError(
                        structured.code(),
                        structured.getMessage(),
                        structured.retryAfter().toMillis()
                );
            }
            return new RpcError(error == null ? "" : error.getMessage());
        }

        @Override
        public String toString() {
            return message;
        }
    }
}
