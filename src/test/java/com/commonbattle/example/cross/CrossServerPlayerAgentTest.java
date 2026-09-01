package com.commonbattle.example.cross;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;
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
        assertEquals("scene.enter", gateway.request.operation());
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
        assertEquals("scene unavailable", agent.lastError());
    }

    private static final class RecordingGateway implements RpcGateway {
        private RpcRequest<EnterSceneResult> request;
        private RpcCallback<EnterSceneResult> callback;

        @Override
        @SuppressWarnings("unchecked")
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            this.request = (RpcRequest<EnterSceneResult>) request;
            this.callback = (RpcCallback<EnterSceneResult>) callback;
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
