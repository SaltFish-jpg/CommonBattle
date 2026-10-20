package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.lifecycle.AgentAlreadyOwnedException;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcResponder;
import com.commonbattle.cluster.rpc.RpcStructuredException;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 基于 AgentDirectory 的非阻塞实体 RPC 路由器。
 * 入站线程只查询/转发或触发本地邮箱投递，业务执行仍由具体 Agent mailbox 完成。
 */
public final class AgentOwnerRpcRouter {
    private static final Duration OWNER_RACE_RETRY_AFTER = Duration.ofMillis(20);

    private final ServiceId localServiceId;
    private final RemoteAgentDirectory directory;
    private final ClusterRpcGateway gateway;

    public AgentOwnerRpcRouter(
            ServiceId localServiceId,
            RemoteAgentDirectory directory,
            ClusterRpcGateway gateway
    ) {
        this.localServiceId = Objects.requireNonNull(localServiceId, "localServiceId");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public <T> void route(
            AgentIdentity identity,
            String operation,
            Object payload,
            Class<T> responseType,
            RpcResponder responder,
            Runnable localEnqueue
    ) {
        route(identity, operation, payload, responseType, responder, failure -> {
            try {
                localEnqueue.run();
            } catch (RuntimeException error) {
                failure.accept(error);
            }
        });
    }

    public <T> void route(
            AgentIdentity identity,
            String operation,
            Object payload,
            Class<T> responseType,
            RpcResponder responder,
            OwnerRoutedEnqueue localEnqueue
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(responder, "responder");
        Objects.requireNonNull(localEnqueue, "localEnqueue");
        directory.locateAsync(
                identity,
                owner -> routeLocated(identity, operation, payload, responseType, responder, localEnqueue, owner, true),
                responder::failure
        );
    }

    private <T> void routeLocated(
            AgentIdentity identity,
            String operation,
            Object payload,
            Class<T> responseType,
            RpcResponder responder,
            OwnerRoutedEnqueue localEnqueue,
            Optional<AgentLocation> owner,
            boolean retryOwnerRace
    ) {
        if (owner.isPresent() && !owner.orElseThrow().isLocal(localServiceId)) {
            forward(owner.orElseThrow(), operation, payload, responseType, responder);
            return;
        }
        try {
            localEnqueue.enqueue(error -> handleLocalFailure(
                    identity, operation, payload, responseType, responder, localEnqueue, retryOwnerRace, error
            ));
        } catch (RuntimeException error) {
            handleLocalFailure(
                    identity, operation, payload, responseType, responder, localEnqueue, retryOwnerRace, error
            );
        }
    }

    private <T> void handleLocalFailure(
            AgentIdentity identity,
            String operation,
            Object payload,
            Class<T> responseType,
            RpcResponder responder,
            OwnerRoutedEnqueue localEnqueue,
            boolean retryOwnerRace,
            Throwable error
    ) {
        if (!(error instanceof AgentAlreadyOwnedException)) {
            responder.failure(error);
            return;
        }
        if (!retryOwnerRace) {
            responder.failure(RpcStructuredException.rejected(
                    "agent_owner_race", OWNER_RACE_RETRY_AFTER
            ));
            return;
        }
        directory.locateAsync(
                identity,
                next -> routeLocated(
                        identity, operation, payload, responseType, responder, localEnqueue, next, false
                ),
                responder::failure
        );
    }

    private <T> void forward(
            AgentLocation owner,
            String operation,
            Object payload,
            Class<T> responseType,
            RpcResponder responder
    ) {
        gateway.call(RpcRequest.toService(owner.serviceId(), operation, payload, responseType), new RpcCallback<>() {
            @Override
            public void success(T response) {
                responder.success(response);
            }

            @Override
            public void failure(Throwable error) {
                responder.failure(error);
            }
        });
    }

    /**
     * 发起本地邮箱投递；异步激活或投递失败必须交给 failure，路由器才能处理 owner 竞争。
     */
    @FunctionalInterface
    public interface OwnerRoutedEnqueue {
        void enqueue(Consumer<Throwable> failure);
    }
}
