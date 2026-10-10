package com.commonbattle.example.cross;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.actor.rpc.ActorRpcHandler;
import com.commonbattle.actor.rpc.ActorRpcClientStats;
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
    private final CrossServerActorRpcClient rpc;
    private final long playerId;
    private long rpcSequence;
    private volatile AgentStatus status = AgentStatus.LOCAL;
    private volatile String sceneId;
    private volatile int sceneChunkX;
    private volatile int sceneChunkY;
    private volatile String lastError;
    private volatile AgentDeliveryStatus lastDeliveryStatus;
    private volatile EnterSceneFailure lastEnterSceneFailure;
    private volatile String lastEnterSceneRpcKey;
    private volatile MoveSceneResult lastSceneMove;
    private volatile CombatDamageResult lastCombatDamage;

    public CrossServerPlayerAgent(ActorSystem system, RpcGateway gateway, long playerId) {
        this.system = system;
        this.self = system.actor("player-agent-" + playerId);
        this.rpc = new CrossServerActorRpcClient(system, self, gateway, new ClusterRpcDeliveryFailureMapper());
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

    public int sceneChunkX() {
        return sceneChunkX;
    }

    public int sceneChunkY() {
        return sceneChunkY;
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

    public MoveSceneResult lastSceneMove() {
        return lastSceneMove;
    }

    public CombatDamageResult lastCombatDamage() {
        return lastCombatDamage;
    }

    public ActorRpcClientStats rpcStats() {
        return rpc.stats();
    }

    public void enterScene(String targetSceneId) {
        enterScene(targetSceneId, 0, 0);
    }

    public void enterScene(String targetSceneId, int chunkX, int chunkY) {
        system.send(self, context -> beginEnterScene(targetSceneId, chunkX, chunkY,
                nextIdempotencyKey(SceneOperations.ENTER, targetSceneId + ":" + chunkX + ":" + chunkY)));
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
            String idempotencyKey = lastEnterSceneRpcKey == null || lastEnterSceneRpcKey.isBlank()
                    ? nextIdempotencyKey(SceneOperations.ENTER, sceneId + ":" + sceneChunkX + ":" + sceneChunkY)
                    : lastEnterSceneRpcKey;
            beginEnterScene(sceneId, sceneChunkX, sceneChunkY, idempotencyKey);
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
            rpc.call(request, rpcOptions(SceneOperations.LEAVE, sceneId), new ActorRpcHandler<>() {
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

    public void moveScene(int chunkX, int chunkY) {
        system.send(self, context -> {
            if (status != AgentStatus.IN_SCENE || sceneId == null) {
                lastError = "player is not in scene";
                return;
            }
            lastError = null;
            lastDeliveryStatus = null;
            lastSceneMove = null;
            RpcRequest<MoveSceneResult> request = new RpcRequest<>(
                    ServiceKind.SCENE.name(),
                    SceneOperations.MOVE,
                    new MoveSceneRequest(playerId, sceneId, chunkX, chunkY),
                    MoveSceneResult.class
            );
            rpc.call(request, rpcOptions(SceneOperations.MOVE, sceneId + ":" + chunkX + ":" + chunkY),
                    new ActorRpcHandler<>() {
                @Override
                public void success(com.commonbattle.actor.ActorContext ignored, MoveSceneResult result) {
                    onMoveSceneSuccess(ignored, result);
                }

                @Override
                public void failure(com.commonbattle.actor.ActorContext ignored, AgentDeliveryResult delivery, Throwable error) {
                    onMoveSceneFailure(ignored, delivery, error);
                }
            });
        });
    }

    public void attackSceneTarget(long targetId, int damage) {
        system.send(self, context -> {
            if (status != AgentStatus.IN_SCENE || sceneId == null) {
                lastError = "player is not in scene";
                return;
            }
            lastError = null;
            lastDeliveryStatus = null;
            lastCombatDamage = null;
            RpcRequest<CombatDamageResult> request = new RpcRequest<>(
                    ServiceKind.SCENE.name(),
                    SceneOperations.COMBAT_DAMAGE,
                    new CombatDamageRequest(sceneId, playerId, targetId, damage),
                    CombatDamageResult.class
            );
            rpc.call(request, rpcOptions(SceneOperations.COMBAT_DAMAGE, sceneId + ":" + targetId + ":" + damage),
                    new ActorRpcHandler<>() {
                @Override
                public void success(com.commonbattle.actor.ActorContext ignored, CombatDamageResult result) {
                    onCombatDamageSuccess(ignored, result);
                }

                @Override
                public void failure(com.commonbattle.actor.ActorContext ignored, AgentDeliveryResult delivery, Throwable error) {
                    onCombatDamageFailure(ignored, delivery, error);
                }
            });
        });
    }

    private void beginEnterScene(String targetSceneId, int chunkX, int chunkY, String idempotencyKey) {
        status = AgentStatus.ENTERING_SCENE;
        sceneId = targetSceneId;
        sceneChunkX = chunkX;
        sceneChunkY = chunkY;
        lastError = null;
        lastDeliveryStatus = null;
        lastEnterSceneFailure = null;
        lastEnterSceneRpcKey = idempotencyKey;
        lastSceneMove = null;
        RpcRequest<EnterSceneResult> request = new RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                new EnterSceneRequest(playerId, targetSceneId, chunkX, chunkY),
                EnterSceneResult.class
        );
        rpc.call(request, CrossServerRpcOptions.defaults().withIdempotencyKey(idempotencyKey), new ActorRpcHandler<>() {
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
        lastEnterSceneRpcKey = null;
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
        sceneChunkX = 0;
        sceneChunkY = 0;
    }

    private void onLeaveSceneFailure(Object ignored, AgentDeliveryResult delivery, Throwable error) {
        status = AgentStatus.FAILED;
        lastDeliveryStatus = delivery.status();
        lastError = delivery.reason();
    }

    private void onMoveSceneSuccess(Object ignored, MoveSceneResult result) {
        lastSceneMove = result;
        if (!result.moved()) {
            lastError = "scene move rejected";
            return;
        }
        sceneId = result.sceneId();
        sceneChunkX = result.chunkX();
        sceneChunkY = result.chunkY();
    }

    private void onMoveSceneFailure(Object ignored, AgentDeliveryResult delivery, Throwable error) {
        lastDeliveryStatus = delivery.status();
        lastError = delivery.reason();
    }

    private void onCombatDamageSuccess(Object ignored, CombatDamageResult result) {
        lastCombatDamage = result;
        if (!result.applied()) {
            lastError = "scene combat damage rejected";
        }
    }

    private void onCombatDamageFailure(Object ignored, AgentDeliveryResult delivery, Throwable error) {
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

    private CrossServerRpcOptions rpcOptions(String operation, String discriminator) {
        return CrossServerRpcOptions.defaults().withIdempotencyKey(nextIdempotencyKey(operation, discriminator));
    }

    private String nextIdempotencyKey(String operation, String discriminator) {
        rpcSequence++;
        return "player:" + playerId + ":" + operation + ":" + rpcSequence + ":" + discriminator;
    }
}
