package com.commonbattle.example.cross;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.actor.message.RemoteCallFailureMapper;
import com.commonbattle.actor.rpc.ActorRpcClientStats;
import com.commonbattle.actor.rpc.ActorRpcHandler;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.rpc.OptionedRpcGateway;
import com.commonbattle.cluster.rpc.RpcCallOptions;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * 绑定到业务 Actor 的跨服 RPC 客户端。
 * 网络回调线程只负责把结果重新投递到 owner 邮箱，超时、幂等键等治理参数通过支持选项的网关下发。
 */
public final class CrossServerActorRpcClient {
    private final ActorSystem system;
    private final ActorRef owner;
    private final RpcGateway gateway;
    private final RemoteCallFailureMapper failures;
    private final LongAdder calls = new LongAdder();
    private final LongAdder succeededResponses = new LongAdder();
    private final LongAdder failedResponses = new LongAdder();
    private final LongAdder callbackDeliveryFailures = new LongAdder();
    private final EnumMap<AgentDeliveryStatus, LongAdder> failedResponsesByStatus =
            new EnumMap<>(AgentDeliveryStatus.class);

    public CrossServerActorRpcClient(
            ActorSystem system,
            ActorRef owner,
            RpcGateway gateway,
            RemoteCallFailureMapper failures
    ) {
        this.system = Objects.requireNonNull(system, "system");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.failures = Objects.requireNonNull(failures, "failures");
        for (AgentDeliveryStatus status : AgentDeliveryStatus.values()) {
            failedResponsesByStatus.put(status, new LongAdder());
        }
    }

    public <T> void call(RpcRequest<T> request, CrossServerRpcOptions options, ActorRpcHandler<T> handler) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(handler, "handler");
        calls.increment();
        RpcCallback<T> callback = new RpcCallback<>() {
            @Override
            public void success(T response) {
                succeededResponses.increment();
                enqueueCallback(context -> handler.success(context, response));
            }

            @Override
            public void failure(Throwable error) {
                AgentDeliveryResult delivery = failures.map(error);
                failedResponses.increment();
                failedResponsesByStatus.get(delivery.status()).increment();
                enqueueCallback(context -> handler.failure(context, delivery, error));
            }
        };
        try {
            if (gateway instanceof OptionedRpcGateway optioned) {
                optioned.call(request, callback,
                        RpcCallOptions.of(options.timeout()).withIdempotencyKey(options.idempotencyKey()));
                return;
            }
            gateway.call(request, callback);
        } catch (RuntimeException e) {
            callback.failure(e);
        }
    }

    public ActorRpcClientStats stats() {
        EnumMap<AgentDeliveryStatus, Long> failures = new EnumMap<>(AgentDeliveryStatus.class);
        failedResponsesByStatus.forEach((status, count) -> failures.put(status, count.sum()));
        return new ActorRpcClientStats(
                calls.sum(),
                succeededResponses.sum(),
                failedResponses.sum(),
                callbackDeliveryFailures.sum(),
                Map.copyOf(failures)
        );
    }

    private void enqueueCallback(ActorTask task) {
        boolean accepted = system.trySend(owner, ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK, task));
        if (!accepted) {
            callbackDeliveryFailures.increment();
        }
    }
}
