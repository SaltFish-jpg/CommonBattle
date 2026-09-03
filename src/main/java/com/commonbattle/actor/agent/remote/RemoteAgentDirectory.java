package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过中心服 RPC 访问的 AgentDirectory。
 * Game、Scene、Chat 进程用它共享 owner 目录，业务 Actor 仍只看到本地同步目录接口。
 */
public final class RemoteAgentDirectory implements AgentDirectory {
    private final ClusterRpcGateway gateway;

    public RemoteAgentDirectory(ClusterRpcGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public boolean claim(AgentIdentity identity, AgentLocation location) {
        AgentDirectoryBooleanResponse response = await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                AgentDirectoryOperations.CLAIM,
                new AgentDirectoryClaimRequest(identity, location),
                AgentDirectoryBooleanResponse.class
        ));
        return response.accepted();
    }

    @Override
    public boolean move(AgentIdentity identity, AgentLocation expectedCurrent, AgentLocation next) {
        AgentDirectoryBooleanResponse response = await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                AgentDirectoryOperations.MOVE,
                new AgentDirectoryMoveRequest(identity, expectedCurrent, next),
                AgentDirectoryBooleanResponse.class
        ));
        return response.accepted();
    }

    @Override
    public void unbind(AgentIdentity identity, AgentLocation location) {
        await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                AgentDirectoryOperations.UNBIND,
                new AgentDirectoryUnbindRequest(identity, location),
                AgentDirectoryBooleanResponse.class
        ));
    }

    @Override
    public Optional<AgentLocation> locate(AgentIdentity identity) {
        AgentDirectoryLocateResponse response = await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                AgentDirectoryOperations.LOCATE,
                new AgentDirectoryLocateRequest(identity),
                AgentDirectoryLocateResponse.class
        ));
        return response.located();
    }

    private <T> T await(RpcRequest<T> request) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        gateway.call(request, new RpcCallback<>() {
            @Override
            public void success(T response) {
                value.set(response);
                done.countDown();
            }

            @Override
            public void failure(Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });
        try {
            if (!done.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Agent directory RPC timeout: " + request.operation());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting agent directory RPC", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Agent directory RPC failed: " + request.operation(), failure.get());
        }
        return value.get();
    }
}
