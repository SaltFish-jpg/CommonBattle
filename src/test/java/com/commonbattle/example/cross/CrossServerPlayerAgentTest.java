package com.commonbattle.example.cross;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.rpc.RpcCircuitOpenException;
import com.commonbattle.cluster.rpc.RpcTimeoutException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

        gateway.callback.success(new EnterSceneResult(10001L, "scene-9", 7001L));
        assertEquals(AgentStatus.ENTERING_SCENE, agent.status());

        executor.runNext();
        assertEquals(AgentStatus.IN_SCENE, agent.status());
        assertEquals("scene-9", agent.sceneId());
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

        @Override
        @SuppressWarnings("unchecked")
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
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
