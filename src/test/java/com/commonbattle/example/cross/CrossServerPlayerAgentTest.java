package com.commonbattle.example.cross;




import com.commonbattle.battle.command.Command;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.rpc.OptionedRpcGateway;
import com.commonbattle.cluster.rpc.RpcCircuitOpenException;
import com.commonbattle.cluster.rpc.RpcCallOptions;
import com.commonbattle.cluster.rpc.RpcNoRoutableServiceException;
import com.commonbattle.cluster.rpc.RpcTimeoutException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerPlayerAgentTest {
    @Test
    void rpcResponseResumesPlayerAgentThroughMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.enterScene("scene-9");
        executor.runNext();

        assertEquals(AgentStatus.ENTERING_SCENE, agent.status());
        assertEquals(ServiceKind.SCENE.name(), gateway.request.target());
        assertEquals(SceneOperations.ENTER, gateway.request.operation());
        EnterSceneRequest payload = assertInstanceOf(EnterSceneRequest.class, gateway.request.payload());
        assertEquals(10001L, payload.playerId());
        assertEquals("scene-9", payload.sceneId());
        assertEquals(0, payload.chunkX());
        assertEquals(0, payload.chunkY());

        gateway.callback.success(new EnterSceneResult(10001L, "scene-9", 7001L));
        assertEquals(AgentStatus.ENTERING_SCENE, agent.status());

        executor.runNext();
        assertEquals(AgentStatus.IN_SCENE, agent.status());
        assertEquals("scene-9", agent.sceneId());
    }

    @Test
    void enterSceneCanCarryLargeSceneChunkCoordinatesAndRetryKeepsThem() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.enterScene("world-1", 12, -4);
        executor.runNext();

        EnterSceneRequest first = assertInstanceOf(EnterSceneRequest.class, gateway.request.payload());
        assertEquals(12, first.chunkX());
        assertEquals(-4, first.chunkY());

        gateway.failure(new RpcNoRoutableServiceException(gateway.request));
        executor.runNext();
        agent.retryEnterScene();
        executor.runNext();

        EnterSceneRequest retry = assertInstanceOf(EnterSceneRequest.class, gateway.request.payload());
        assertEquals("world-1", retry.sceneId());
        assertEquals(12, retry.chunkX());
        assertEquals(-4, retry.chunkY());
    }

    @Test
    void crossServerRpcUsesDefaultTimeoutAndStableEnterRetryIdempotencyKey() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        OptionedRecordingGateway gateway = new OptionedRecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.enterScene("world-1", 12, -4);
        executor.runNext();

        String firstKey = gateway.options.idempotencyKey();
        assertEquals(CrossServerRpcOptions.DEFAULT_TIMEOUT, gateway.options.timeout());
        assertTrue(firstKey.startsWith("player:10001:scene.enter:"));
        assertTrue(firstKey.endsWith(":world-1:12:-4"));

        gateway.failure(new RpcNoRoutableServiceException(gateway.request));
        executor.runNext();
        agent.retryEnterScene();
        executor.runNext();

        assertEquals(firstKey, gateway.options.idempotencyKey());
        assertEquals(2, gateway.calls);
    }

    @Test
    void crossServerRpcGeneratesDistinctIdempotencyKeysForIndependentSceneActions() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        OptionedRecordingGateway gateway = new OptionedRecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);
        agent.enterScene("scene-9");
        executor.runNext();
        gateway.success(new EnterSceneResult(10001L, "scene-9", 7001L));
        executor.runNext();

        agent.moveScene(12, -4);
        executor.runNext();
        String moveKey = gateway.options.idempotencyKey();
        gateway.success(new MoveSceneResult(10001L, "scene-9", 12, -4, 0, 3, true));
        executor.runNext();

        agent.attackSceneTarget(20002L, 35);
        executor.runNext();
        String combatKey = gateway.options.idempotencyKey();

        assertTrue(moveKey.contains(":scene.move:"));
        assertTrue(combatKey.contains(":scene.combat.damage:"));
        assertTrue(!moveKey.equals(combatKey));
        assertEquals(3, agent.rpcStats().calls());
    }

    @Test
    void rpcFailureAlsoReturnsToPlayerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.enterScene("scene-9");
        executor.runNext();
        gateway.callback.failure(new IllegalStateException("scene unavailable"));

        assertEquals(AgentStatus.ENTERING_SCENE, agent.status());
        executor.runNext();

        assertEquals(AgentStatus.FAILED, agent.status());
        assertEquals(AgentDeliveryStatus.REMOTE_UNAVAILABLE, agent.lastDeliveryStatus());
        assertEquals("scene unavailable", agent.lastError());
    }

    @Test
    void rpcTimeoutFailureIsClassifiedForBusinessDegrade() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.enterScene("scene-9");
        executor.runNext();
        gateway.failure(new RpcTimeoutException(gateway.request, java.time.Duration.ofMillis(1)));
        executor.runNext();

        assertEquals(AgentStatus.FAILED, agent.status());
        assertEquals(AgentDeliveryStatus.TIMEOUT, agent.lastDeliveryStatus());
        assertEquals(EnterSceneFailureCode.SCENE_TIMEOUT, agent.lastEnterSceneFailure().code());
        assertTrue(agent.lastEnterSceneFailure().retryable());
    }

    @Test
    void rpcCircuitOpenFailureIsClassifiedForBusinessDegrade() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.enterScene("scene-9");
        executor.runNext();
        gateway.failure(new RpcCircuitOpenException(gateway.request));
        executor.runNext();

        assertEquals(AgentStatus.FAILED, agent.status());
        assertEquals(AgentDeliveryStatus.CIRCUIT_OPEN, agent.lastDeliveryStatus());
        assertEquals(EnterSceneFailureCode.SCENE_BUSY, agent.lastEnterSceneFailure().code());
        assertTrue(agent.lastEnterSceneFailure().retryAfter().toMillis() > 0);
    }

    @Test
    void noRoutableSceneBecomesRetryableBusinessBusyFailure() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.enterScene("scene-9");
        executor.runNext();
        gateway.failure(new RpcNoRoutableServiceException(gateway.request));
        executor.runNext();

        assertEquals(AgentStatus.FAILED, agent.status());
        assertEquals(AgentDeliveryStatus.REMOTE_UNAVAILABLE, agent.lastDeliveryStatus());
        assertEquals(EnterSceneFailureCode.SCENE_BUSY, agent.lastEnterSceneFailure().code());
        assertTrue(agent.lastEnterSceneFailure().retryable());
        assertEquals(1000, agent.lastEnterSceneFailure().retryAfter().toMillis());
    }

    @Test
    void retryEnterSceneReissuesRequestOnlyAfterRetryableEnterFailure() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.enterScene("scene-9");
        executor.runNext();
        gateway.failure(new RpcNoRoutableServiceException(gateway.request));
        executor.runNext();

        assertEquals(1, gateway.calls);

        agent.retryEnterScene();
        executor.runNext();

        assertEquals(AgentStatus.ENTERING_SCENE, agent.status());
        assertEquals(2, gateway.calls);
        EnterSceneRequest payload = assertInstanceOf(EnterSceneRequest.class, gateway.request.payload());
        assertEquals("scene-9", payload.sceneId());
    }

    @Test
    void leaveSceneAlsoResumesPlayerAgentThroughMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);
        enterSuccessfully(executor, gateway, agent);

        agent.leaveScene();
        executor.runNext();

        assertEquals(AgentStatus.LEAVING_SCENE, agent.status());
        assertEquals(SceneOperations.LEAVE, gateway.request.operation());
        LeaveSceneRequest payload = assertInstanceOf(LeaveSceneRequest.class, gateway.request.payload());
        assertEquals(10001L, payload.playerId());
        assertEquals("scene-9", payload.sceneId());

        gateway.success(new LeaveSceneResult(10001L, "scene-9", true));
        assertEquals(AgentStatus.LEAVING_SCENE, agent.status());
        executor.runNext();

        assertEquals(AgentStatus.LOCAL, agent.status());
        assertEquals(null, agent.sceneId());
    }

    @Test
    void sceneMoveResponseReturnsToPlayerMailboxAndUpdatesPosition() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);
        enterSuccessfully(executor, gateway, agent);

        agent.moveScene(12, -4);
        executor.runNext();

        assertEquals(AgentStatus.IN_SCENE, agent.status());
        assertEquals(SceneOperations.MOVE, gateway.request.operation());
        MoveSceneRequest payload = assertInstanceOf(MoveSceneRequest.class, gateway.request.payload());
        assertEquals(10001L, payload.playerId());
        assertEquals("scene-9", payload.sceneId());
        assertEquals(12, payload.chunkX());
        assertEquals(-4, payload.chunkY());

        gateway.success(new MoveSceneResult(10001L, "scene-9", 12, -4, 0, 3, true));
        assertEquals(0, agent.sceneChunkX());
        assertEquals(0, agent.sceneChunkY());
        assertEquals(null, agent.lastSceneMove());

        executor.runNext();
        assertEquals(12, agent.sceneChunkX());
        assertEquals(-4, agent.sceneChunkY());
        assertEquals(3, agent.lastSceneMove().shardIndex());
        assertEquals(null, agent.lastError());
    }

    @Test
    void sceneMoveFailureReturnsToPlayerMailboxWithoutChangingPosition() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);
        enterSuccessfully(executor, gateway, agent);

        agent.moveScene(12, -4);
        executor.runNext();
        gateway.failure(new IllegalStateException("scene move unavailable"));

        assertEquals(0, agent.sceneChunkX());
        assertEquals(0, agent.sceneChunkY());
        executor.runNext();

        assertEquals(AgentStatus.IN_SCENE, agent.status());
        assertEquals(0, agent.sceneChunkX());
        assertEquals(0, agent.sceneChunkY());
        assertEquals(AgentDeliveryStatus.REMOTE_UNAVAILABLE, agent.lastDeliveryStatus());
        assertEquals("scene move unavailable", agent.lastError());
    }

    @Test
    void sceneMoveWithoutSceneDoesNotIssueRpc() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.moveScene(12, -4);
        executor.runNext();

        assertEquals("player is not in scene", agent.lastError());
        assertEquals(null, gateway.request);
    }

    @Test
    void sceneCombatDamageResponseReturnsToPlayerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);
        enterSuccessfully(executor, gateway, agent);

        agent.attackSceneTarget(20002L, 35);
        executor.runNext();

        assertEquals(AgentStatus.IN_SCENE, agent.status());
        assertEquals(SceneOperations.COMBAT_DAMAGE, gateway.request.operation());
        CombatDamageRequest payload = assertInstanceOf(CombatDamageRequest.class, gateway.request.payload());
        assertEquals("scene-9", payload.sceneId());
        assertEquals(10001L, payload.attackerId());
        assertEquals(20002L, payload.targetId());
        assertEquals(35, payload.damage());

        gateway.success(new CombatDamageResult("scene-9", 10001L, 20002L, 0, 35, 80, 45, true));
        assertEquals(null, agent.lastCombatDamage());

        executor.runNext();
        assertEquals(AgentStatus.IN_SCENE, agent.status());
        assertEquals(45, agent.lastCombatDamage().hpAfter());
        assertEquals(null, agent.lastError());
    }

    @Test
    void sceneCombatDamageFailureReturnsToPlayerMailboxWithoutLeavingScene() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);
        enterSuccessfully(executor, gateway, agent);

        agent.attackSceneTarget(20002L, 35);
        executor.runNext();
        gateway.failure(new IllegalStateException("scene combat unavailable"));

        assertEquals(AgentStatus.IN_SCENE, agent.status());
        executor.runNext();

        assertEquals(AgentStatus.IN_SCENE, agent.status());
        assertEquals(AgentDeliveryStatus.REMOTE_UNAVAILABLE, agent.lastDeliveryStatus());
        assertEquals("scene combat unavailable", agent.lastError());
    }

    @Test
    void sceneCombatDamageWithoutSceneDoesNotIssueRpc() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.attackSceneTarget(20002L, 35);
        executor.runNext();

        assertEquals("player is not in scene", agent.lastError());
        assertEquals(null, gateway.request);
    }

    @Test
    void leaveSceneFailureReturnsToPlayerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);
        enterSuccessfully(executor, gateway, agent);

        agent.leaveScene();
        executor.runNext();
        gateway.failure(new IllegalStateException("leave timeout"));

        assertEquals(AgentStatus.LEAVING_SCENE, agent.status());
        executor.runNext();

        assertEquals(AgentStatus.FAILED, agent.status());
        assertEquals(AgentDeliveryStatus.REMOTE_UNAVAILABLE, agent.lastDeliveryStatus());
        assertEquals("leave timeout", agent.lastError());
        assertEquals("scene-9", agent.sceneId());
    }

    @Test
    void leaveSceneWithoutSceneDoesNotIssueRpc() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        RecordingGateway gateway = new RecordingGateway();
        CrossServerPlayerAgent agent = new CrossServerPlayerAgent(system, gateway, 10001L);

        agent.leaveScene();
        executor.runNext();

        assertEquals(AgentStatus.LOCAL, agent.status());
        assertEquals("player is not in scene", agent.lastError());
        assertEquals(null, gateway.request);
    }

    private static void enterSuccessfully(RecordingExecutor executor, RecordingGateway gateway, CrossServerPlayerAgent agent) {
        agent.enterScene("scene-9");
        executor.runNext();
        gateway.success(new EnterSceneResult(10001L, "scene-9", 7001L));
        executor.runNext();
    }

    private static final class RecordingGateway implements RpcGateway {
        private RpcRequest<?> request;
        private RpcCallback<Object> callback;
        private int calls;

        @Override
        @SuppressWarnings("unchecked")
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            calls++;
            this.request = request;
            this.callback = (RpcCallback<Object>) callback;
        }

        void success(Object response) {
            callback.success(response);
        }

        void failure(Throwable error) {
            callback.failure(error);
        }
    }

    private static final class OptionedRecordingGateway implements OptionedRpcGateway {
        private RpcRequest<?> request;
        private RpcCallback<Object> callback;
        private RpcCallOptions options;
        private int calls;

        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            call(request, callback, RpcCallOptions.of(CrossServerRpcOptions.DEFAULT_TIMEOUT));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback, RpcCallOptions options) {
            calls++;
            this.request = request;
            this.callback = (RpcCallback<Object>) callback;
            this.options = options;
        }

        void success(Object response) {
            callback.success(response);
        }

        void failure(Throwable error) {
            callback.failure(error);
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }
}
