package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.agent.AgentRoute;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.backpressure.InboundAdmissionController;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcResponder;
import com.commonbattle.cluster.rpc.RpcStructuredException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * 通用业务 Agent RPC 端点。
 * 网络入口只做路由和投递，真正的业务处理必须回到目标 owner Actor 邮箱内执行。
 */
public final class BusinessAgentRpcEndpoint implements BusinessAgentRpcEndpointView {
    private final LifecycleAwareAgentRouter router;
    private final BusinessAgentHandlerRegistry handlers;
    private final InboundAdmissionController admissions;
    private final IdempotencyCache idempotencyCache;

    public BusinessAgentRpcEndpoint(LifecycleAwareAgentRouter router, BusinessAgentHandlerRegistry handlers) {
        this(router, handlers, (target, operation) -> AdmissionDecision.accept());
    }

    public BusinessAgentRpcEndpoint(
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers,
            InboundAdmissionController admissions
    ) {
        this(router, handlers, admissions, BusinessAgentIdempotencyConfig.defaults());
    }

    public BusinessAgentRpcEndpoint(
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers,
            InboundAdmissionController admissions,
            int idempotencyCacheCapacity
    ) {
        this(router, handlers, admissions,
                new BusinessAgentIdempotencyConfig(idempotencyCacheCapacity, BusinessAgentIdempotencyConfig.DEFAULT_TTL));
    }

    public BusinessAgentRpcEndpoint(
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers,
            InboundAdmissionController admissions,
            BusinessAgentIdempotencyConfig idempotencyConfig
    ) {
        this(router, handlers, admissions, idempotencyConfig, Clock.systemUTC());
    }

    public BusinessAgentRpcEndpoint(
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers,
            InboundAdmissionController admissions,
            BusinessAgentIdempotencyConfig idempotencyConfig,
            Clock clock
    ) {
        this.router = Objects.requireNonNull(router, "router");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.idempotencyCache = new IdempotencyCache(idempotencyConfig, clock);
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(BusinessAgentRpcOperations.DISPATCH, (envelope, responder) -> {
            BusinessAgentRequest request = (BusinessAgentRequest) envelope.payload();
            IdempotencyKey idempotencyKey = idempotencyKey(envelope.source(), request);
            IdempotencyEntry entry = null;
            if (idempotencyKey != null) {
                entry = idempotencyCache.begin(idempotencyKey, responder);
                if (entry == null) {
                    return;
                }
                if (entry.completed()) {
                    entry.replay(responder);
                    return;
                }
            }
            AdmissionDecision admission = admissions.admit(request.target(), request.operation());
            if (!admission.accepted()) {
                completeFailure(idempotencyKey, responder,
                        RpcStructuredException.rejected(admission.reason(), admission.retryAfter()));
                return;
            }
            AgentRoute route = router.resolve(request.target());
            if (route.type() != AgentRouteType.LOCAL) {
                completeFailure(idempotencyKey, responder,
                        new BusinessAgentRequestException(request.target(), request.operation(), route.reason()));
                return;
            }
            AgentDeliveryResult delivery = router.tellResolvedLocal(route, ActorTask.categorized(
                    com.commonbattle.actor.ActorTaskCategory.DEFAULT,
                    context -> {
                        try {
                            completeSuccess(idempotencyKey, responder, handlers.dispatch(context, request));
                        } catch (RuntimeException | Error e) {
                            completeFailure(idempotencyKey, responder, e);
                        }
                    }
            ));
            if (!delivery.accepted()) {
                completeFailure(idempotencyKey, responder,
                        RpcStructuredException.rejected(delivery.reason(), delivery.retryAfter()));
            }
        });
    }

    @Override
    public BusinessAgentRpcEndpointStats stats() {
        return idempotencyCache.stats();
    }

    private void completeSuccess(IdempotencyKey key, RpcResponder responder, Object payload) {
        if (key == null) {
            responder.success(payload);
            return;
        }
        idempotencyCache.complete(key, IdempotencyResult.success(payload), responder);
    }

    private void completeFailure(IdempotencyKey key, RpcResponder responder, Throwable error) {
        if (key == null) {
            responder.failure(error);
            return;
        }
        idempotencyCache.complete(key, IdempotencyResult.failure(error), responder);
    }

    private static IdempotencyKey idempotencyKey(ServiceId source, BusinessAgentRequest request) {
        if (request.idempotencyKey().isBlank()) {
            return null;
        }
        return new IdempotencyKey(source, request.target(), request.operation(), request.idempotencyKey());
    }

    private record IdempotencyKey(ServiceId source, AgentIdentity target, String operation, String key) {
    }

    private record IdempotencyResult(boolean success, Object payload, Throwable error) {
        static IdempotencyResult success(Object payload) {
            return new IdempotencyResult(true, payload, null);
        }

        static IdempotencyResult failure(Throwable error) {
            return new IdempotencyResult(false, null, error);
        }

        void reply(RpcResponder responder) {
            if (success) {
                responder.success(payload);
            } else {
                responder.failure(error);
            }
        }
    }

    private static final class IdempotencyEntry {
        private final List<RpcResponder> waiters = new ArrayList<>();
        private IdempotencyResult result;
        private long completedAtMillis;

        private IdempotencyEntry(RpcResponder first) {
            waiters.add(first);
        }

        private boolean completed() {
            return result != null;
        }

        private void replay(RpcResponder responder) {
            result.reply(responder);
        }
    }

    private static final class IdempotencyCache {
        private final BusinessAgentIdempotencyConfig config;
        private final Clock clock;
        private final LinkedHashMap<IdempotencyKey, IdempotencyEntry> entries;
        private final LongAdder startedRequests = new LongAdder();
        private final LongAdder joinedInFlightRequests = new LongAdder();
        private final LongAdder completedCacheHits = new LongAdder();
        private final LongAdder expiredEntries = new LongAdder();
        private final LongAdder evictedEntries = new LongAdder();
        private final LongAdder completedSuccesses = new LongAdder();
        private final LongAdder completedFailures = new LongAdder();

        private IdempotencyCache(BusinessAgentIdempotencyConfig config, Clock clock) {
            this.config = Objects.requireNonNull(config, "config");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.entries = new LinkedHashMap<>(Math.max(1, config.capacity()), 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<IdempotencyKey, IdempotencyEntry> eldest) {
                    boolean remove = IdempotencyCache.this.config.capacity() > 0
                            && size() > IdempotencyCache.this.config.capacity()
                            && eldest.getValue().completed();
                    if (remove) {
                        evictedEntries.increment();
                    }
                    return remove;
                }
            };
        }

        private synchronized IdempotencyEntry begin(IdempotencyKey key, RpcResponder responder) {
            if (config.capacity() == 0) {
                startedRequests.increment();
                return new IdempotencyEntry(responder);
            }
            IdempotencyEntry entry = entries.get(key);
            if (entry == null) {
                entry = new IdempotencyEntry(responder);
                entries.put(key, entry);
                startedRequests.increment();
                return entry;
            }
            if (entry.completed()) {
                if (expired(entry)) {
                    entries.remove(key);
                    expiredEntries.increment();
                    IdempotencyEntry replacement = new IdempotencyEntry(responder);
                    entries.put(key, replacement);
                    startedRequests.increment();
                    return replacement;
                }
                completedCacheHits.increment();
                return entry;
            }
            entry.waiters.add(responder);
            joinedInFlightRequests.increment();
            return null;
        }

        private void complete(IdempotencyKey key, IdempotencyResult result, RpcResponder responder) {
            List<RpcResponder> waiters;
            synchronized (this) {
                IdempotencyEntry entry = entries.get(key);
                if (entry == null) {
                    if (result.success()) {
                        completedSuccesses.increment();
                    } else {
                        completedFailures.increment();
                    }
                    result.reply(responder);
                    return;
                }
                entry.result = result;
                entry.completedAtMillis = clock.millis();
                waiters = List.copyOf(entry.waiters);
                entry.waiters.clear();
                if (result.success()) {
                    completedSuccesses.increment();
                } else {
                    completedFailures.increment();
                }
            }
            for (RpcResponder waiter : waiters) {
                result.reply(waiter);
            }
        }

        private boolean expired(IdempotencyEntry entry) {
            if (config.ttl().isZero()) {
                return false;
            }
            return clock.millis() - entry.completedAtMillis >= config.ttl().toMillis();
        }

        private synchronized BusinessAgentRpcEndpointStats stats() {
            return new BusinessAgentRpcEndpointStats(
                    1,
                    config.capacity(),
                    config.ttl().toMillis(),
                    entries.size(),
                    startedRequests.sum(),
                    joinedInFlightRequests.sum(),
                    completedCacheHits.sum(),
                    expiredEntries.sum(),
                    evictedEntries.sum(),
                    completedSuccesses.sum(),
                    completedFailures.sum()
            );
        }
    }
}
