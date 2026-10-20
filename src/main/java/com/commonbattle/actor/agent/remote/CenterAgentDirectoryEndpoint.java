package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.LeasedAgentDirectory;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.time.Duration;
import java.util.Objects;

/**
 * 中心服 AgentDirectory RPC 端点。
 * 它把远程 claim、move、unbind、locate 映射到底层目录实现，目录实现继续负责 owner 唯一性和 CAS 语义。
 */
public final class CenterAgentDirectoryEndpoint {
    private final AgentDirectory directory;

    public CenterAgentDirectoryEndpoint(AgentDirectory directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public void bind(ClusterRpcGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(AgentDirectoryOperations.CLAIM, (request, responder) -> {
            AgentDirectoryClaimRequest payload = (AgentDirectoryClaimRequest) request.payload();
            responder.success(new AgentDirectoryBooleanResponse(directory.claim(payload.identity(), payload.location())));
        });
        gateway.handle(AgentDirectoryOperations.MOVE, (request, responder) -> {
            AgentDirectoryMoveRequest payload = (AgentDirectoryMoveRequest) request.payload();
            responder.success(new AgentDirectoryBooleanResponse(
                    directory.move(payload.identity(), payload.expectedCurrent(), payload.next())
            ));
        });
        gateway.handle(AgentDirectoryOperations.UNBIND, (request, responder) -> {
            AgentDirectoryUnbindRequest payload = (AgentDirectoryUnbindRequest) request.payload();
            directory.unbind(payload.identity(), payload.location());
            responder.success(new AgentDirectoryBooleanResponse(true));
        });
        gateway.handle(AgentDirectoryOperations.LOCATE, (request, responder) -> {
            AgentDirectoryLocateRequest payload = (AgentDirectoryLocateRequest) request.payload();
            responder.success(new AgentDirectoryLocateResponse(directory.locate(payload.identity()).orElse(null)));
        });
        gateway.handle(AgentDirectoryOperations.ACQUIRE_LEASE, (request, responder) -> {
            AgentDirectoryLeaseAcquireRequest payload = (AgentDirectoryLeaseAcquireRequest) request.payload();
            responder.success(new AgentDirectoryLeaseResponse(leasedDirectory().acquire(
                    payload.identity(),
                    payload.location(),
                    payload.ownerSession(),
                    Duration.ofMillis(payload.leaseTtlMillis())
            ).orElse(null)));
        });
        gateway.handle(AgentDirectoryOperations.RENEW_LEASE, (request, responder) -> {
            AgentDirectoryLeaseRenewRequest payload = (AgentDirectoryLeaseRenewRequest) request.payload();
            responder.success(new AgentDirectoryLeaseResponse(leasedDirectory().renew(
                    payload.lease(), Duration.ofMillis(payload.leaseTtlMillis())
            ).orElse(null)));
        });
        gateway.handle(AgentDirectoryOperations.TRANSFER_LEASE, (request, responder) -> {
            AgentDirectoryLeaseTransferRequest payload = (AgentDirectoryLeaseTransferRequest) request.payload();
            responder.success(new AgentDirectoryLeaseResponse(leasedDirectory().transfer(
                    payload.lease(),
                    payload.nextLocation(),
                    payload.nextOwnerSession(),
                    Duration.ofMillis(payload.leaseTtlMillis())
            ).orElse(null)));
        });
        gateway.handle(AgentDirectoryOperations.RELEASE_LEASE, (request, responder) -> {
            AgentDirectoryLeaseReleaseRequest payload = (AgentDirectoryLeaseReleaseRequest) request.payload();
            responder.success(new AgentDirectoryBooleanResponse(leasedDirectory().release(payload.lease())));
        });
        gateway.handle(AgentDirectoryOperations.LOCATE_LEASE, (request, responder) -> {
            AgentDirectoryLocateRequest payload = (AgentDirectoryLocateRequest) request.payload();
            responder.success(new AgentDirectoryLeaseResponse(
                    leasedDirectory().locateLease(payload.identity()).orElse(null)
            ));
        });
    }

    private LeasedAgentDirectory leasedDirectory() {
        if (directory instanceof LeasedAgentDirectory leased) {
            return leased;
        }
        throw new IllegalStateException("Agent directory lease operations are not configured");
    }
}
