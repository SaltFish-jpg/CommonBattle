package com.commonbattle.example.cross;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.rpc.ActorRpcClient;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;

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

    public CrossServerPlayerAgent(ActorSystem system, RpcGateway gateway, long playerId) {
        this.system = system;
        this.self = system.actor("player-agent-" + playerId);
        this.rpc = new ActorRpcClient(system, self, gateway);
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

    public void enterScene(String targetSceneId) {
        system.send(self, context -> {
            status = AgentStatus.ENTERING_SCENE;
            sceneId = targetSceneId;
            lastError = null;
            RpcRequest<EnterSceneResult> request = new RpcRequest<>(
                    ServiceKind.SCENE.name(),
                    SceneOperations.ENTER,
                    new EnterSceneRequest(playerId, targetSceneId),
                    EnterSceneResult.class
            );
            rpc.call(request, this::onEnterSceneSuccess, this::onEnterSceneFailure);
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
            RpcRequest<LeaveSceneResult> request = new RpcRequest<>(
                    ServiceKind.SCENE.name(),
                    SceneOperations.LEAVE,
                    new LeaveSceneRequest(playerId, sceneId),
                    LeaveSceneResult.class
            );
            rpc.call(request, this::onLeaveSceneSuccess, this::onLeaveSceneFailure);
        });
    }

    private void onEnterSceneSuccess(Object ignored, EnterSceneResult result) {
        status = AgentStatus.IN_SCENE;
        sceneId = result.sceneId();
    }

    private void onEnterSceneFailure(Object ignored, Throwable error) {
        status = AgentStatus.FAILED;
        lastError = error.getMessage();
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

    private void onLeaveSceneFailure(Object ignored, Throwable error) {
        status = AgentStatus.FAILED;
        lastError = error.getMessage();
    }
}
