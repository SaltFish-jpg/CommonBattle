package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneAllianceAwarenessAgentTest {
    @Test
    void sceneAppliesAllianceChangeOnlyForOnlinePlayer() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1));
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 20002L, AllianceMemberAction.JOIN, 2));
        executor.runNext();

        assertEquals(100, scene.allianceOf(10001L).orElseThrow().allianceId());
        assertFalse(scene.allianceOf(20002L).isPresent());
    }

    @Test
    void duplicateAllianceEventDoesNotOverwriteNewerSnapshot() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1));
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.LEAVE, 2));
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1));
        executor.runNext();

        assertFalse(scene.allianceOf(10001L).isPresent());
        assertEquals(2, scene.revisionOf(100));
    }

    @Test
    void revisionGapMarksSceneSnapshotStale() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneAllianceAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3));
        executor.runNext();

        ScenePlayerAllianceView view = scene.allianceOf(10001L).orElseThrow();
        assertTrue(view.stale());
        assertEquals(3, view.revision());
    }

    private static SceneAllianceAwarenessAgent createScene(Executor executor) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return new SceneAllianceAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-1")
        );
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

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
