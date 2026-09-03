package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

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
    }
}
