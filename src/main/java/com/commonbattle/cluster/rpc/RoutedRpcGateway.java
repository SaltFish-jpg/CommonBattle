package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceMetadata;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在通用 RpcGateway 外包装一层路由策略。
 * Actor 看到的仍是异步 RPC 出口，策略只在发包前生成灰度、标签、超时等调用选项。
 */
public final class RoutedRpcGateway implements RpcGateway, RpcRoutePolicyView {
    private final OptionedRpcGateway delegate;
    private final RpcCallOptions defaultOptions;
    private final RpcRoutePolicy policy;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong routedCalls = new AtomicLong();
    private final AtomicLong unroutedCalls = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> routeTagCalls = new ConcurrentHashMap<>();

    public RoutedRpcGateway(OptionedRpcGateway delegate, RpcCallOptions defaultOptions, RpcRoutePolicy policy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.defaultOptions = Objects.requireNonNull(defaultOptions, "defaultOptions");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public OptionedRpcGateway delegate() {
        return delegate;
    }

    @Override
    public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");
        RpcCallOptions options = policy.optionsFor(request, defaultOptions);
        record(options);
        delegate.call(request, callback, options);
    }

    @Override
    public RpcRouteStats stats() {
        Map<String, Long> tags = new HashMap<>();
        routeTagCalls.forEach((tag, count) -> tags.put(tag, count.get()));
        return new RpcRouteStats(calls.get(), routedCalls.get(), unroutedCalls.get(), tags);
    }

    private void record(RpcCallOptions options) {
        calls.incrementAndGet();
        String tag = options.requiredTargetMetadata().get(ServiceMetadata.ROUTE_TAG);
        if (tag == null || tag.isBlank()) {
            unroutedCalls.incrementAndGet();
            return;
        }
        routedCalls.incrementAndGet();
        routeTagCalls.computeIfAbsent(tag, ignored -> new AtomicLong()).incrementAndGet();
    }
}
