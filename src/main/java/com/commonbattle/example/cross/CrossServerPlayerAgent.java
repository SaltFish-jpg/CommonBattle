package com.commonbattle.example.cross;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.actor.rpc.ActorRpcHandler;
import com.commonbattle.actor.rpc.ActorRpcClient;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.rpc.ClusterRpcDeliveryFailureMapper;
import com.commonbattle.cluster.rpc.RpcNoRoutableServiceException;

import java.time.Duration;

/**
 * 跨服玩家 Agent 示例。
 * 玩家状态只在自己的 Actor 邮箱中修改；跨服 RPC 回包只负责投递恢复消息，模拟 Skynet 中玩家协程被回包唤醒。
 */
public final class CrossServerPlayerAgent {
    private final ActorSystem system;
    private final ActorRef self;
    private final ActorRpcClient rpc;
    private final long playerId;
    private volatile AgentStatus status = AgentStatus.LOCAL;
    private volatile String sceneId;
    private volatile String lastError;
    private volatile AgentDeliveryStatus lastDeliveryStatus;
    private volatile EnterSceneFailure lastEnterSceneFailure;

    public CrossServerPlayerAgent(ActorSystem system, RpcGateway gateway, long playerId) {
        this.system = system;
        this.self = system.actor("player-agent-" + playerId);
        this.rpc = new ActorRpcClient(system, self, gateway, new ClusterRpcDeliveryFailureMapper());
        this.playerId = playerId;
    }

    public ActorRef ref() {
        return self;
    }

    public AgentStatus status() {
        return status;
    }

    public String sceneId() {
        return sceneId;
    }

    public String lastError() {
        return lastError;
    }

    public AgentDeliveryStatus lastDeliveryStatus() {
        return lastDeliveryStatus;
    }

    public EnterSceneFailure lastEnterSceneFailure() {
        return lastEnterSceneFailure;
    }

    public void enterScene(String targetSceneId) {
        system.send(self, context -> beginEnterScene(targetSceneId));
    }

    public void retryEnterScene() {
        system.send(self, context -> {
            if (status != AgentStatus.FAILED
                    || sceneId == null
                    || lastEnterSceneFailure == null
                    || !lastEnterSceneFailure.retryable()) {
                lastError = "enter scene retry not allowed";
                return;
            }
            beginEnterScene(sceneId);
        });
    }

    public void leaveScene() {
        system.send(self, context -> {
            if (status != AgentStatus.IN_SCENE || sceneId == null) {
                lastError = "player is not in scene";
                return;
            }
            status = AgentStatus.LEAVING_SCENE;
            lastError = null;
            lastDeliveryStatus = null;
            lastEnterSceneFailure = null;
            RpcRequest<LeaveSceneResult> request = new RpcRequest<>(
                    ServiceKind.SCENE.name(),
                    SceneOperations.LEAVE,
                    new LeaveSceneRequest(playerId, sceneId),
                    LeaveSceneResult.class
            );
            rpc.call(request, new ActorRpcHandler<>() {
                @Override
                public void success(com.commonbattle.actor.ActorContext ignored, LeaveSceneResult result) {
                    onLeaveSceneSuccess(ignored, result);
                }

                @Override
                public void failure(com.commonbattle.actor.ActorContext ignored, AgentDeliveryResult delivery, Throwable error) {
                    onLeaveSceneFailure(ignored, delivery, error);
                }
            });
        });
    }

    private void beginEnterScene(String targetSceneId) {
        status = AgentStatus.ENTERING_SCENE;
        sceneId = targetSceneId;
        lastError = null;
        lastDeliveryStatus = null;
        lastEnterSceneFailure = null;
        RpcRequest<EnterSceneResult> request = new RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                new EnterSceneRequest(playerId, targetSceneId),
                EnterSceneResult.class
        );
        rpc.call(request, new ActorRpcHandler<>() {
            @Override
            public void success(com.commonbattle.actor.ActorContext ignored, EnterSceneResult result) {
                onEnterSceneSuccess(ignored, result);
            }

            @Override
            public void failure(com.commonbattle.actor.ActorContext ignored, AgentDeliveryResult delivery, Throwable error) {
                onEnterSceneFailure(ignored, delivery, error);
            }
        });
    }

    private void onEnterSceneSuccess(Object ignored, EnterSceneResult result) {
        status = AgentStatus.IN_SCENE;
        sceneId = result.sceneId();
        lastEnterSceneFailure = null;
    }

    private void onEnterSceneFailure(Object ignored, AgentDeliveryResult delivery, Throwable error) {
        status = AgentStatus.FAILED;
        lastDeliveryStatus = delivery.status();
        lastError = delivery.reason();
        lastEnterSceneFailure = enterSceneFailure(delivery, error);
    }

    private void onLeaveSceneSuccess(Object ignored, LeaveSceneResult result) {
        if (!result.left()) {
            status = AgentStatus.FAILED;
            lastError = "scene leave rejected";
            return;
        }
        status = AgentStatus.LOCAL;
        sceneId = null;
    }

    private void onLeaveSceneFailure(Object ignored, AgentDeliveryResult delivery, Throwable error) {
        status = AgentStatus.FAILED;
        lastDeliveryStatus = delivery.status();
        lastError = delivery.reason();
    }

    private EnterSceneFailure enterSceneFailure(AgentDeliveryResult delivery, Throwable error) {
        EnterSceneFailureCode code = switch (delivery.status()) {
            case TIMEOUT -> EnterSceneFailureCode.SCENE_TIMEOUT;
            case CIRCUIT_OPEN -> EnterSceneFailureCode.SCENE_BUSY;
            case REJECTED -> EnterSceneFailureCode.RPC_REJECTED;
            case REMOTE_UNAVAILABLE -> error instanceof RpcNoRoutableServiceException
                    ? EnterSceneFailureCode.SCENE_BUSY
                    : EnterSceneFailureCode.SCENE_UNAVAILABLE;
            default -> EnterSceneFailureCode.UNKNOWN;
        };
        boolean retryable = delivery.retryable() || code == EnterSceneFailureCode.RPC_REJECTED;
        Duration retryAfter = delivery.retryAfter().isZero() && retryable ? Duration.ofSeconds(1) : delivery.retryAfter();
        return new EnterSceneFailure(code, delivery.reason(), retryable, retryAfter);
    }
}
