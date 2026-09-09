package com.commonbattle.game.social;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.snapshot.SnapshotRepairReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllianceSnapshotRepairerTest {
    @Test
    void repairEnqueuesSnapshotRefreshToSceneActor() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SceneAllianceAwarenessAgent scene = new SceneAllianceAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-alliance")
        );
        AllianceSnapshotRepairer repairer = new AllianceSnapshotRepairer(
                allianceId -> Optional.of(new AllianceSnapshot(allianceId, 3, Set.of(10001L))),
                scene
        );
        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();

        SnapshotRepairReport report = repairer.repair(Set.of(AllianceOwnerKeyParser.ownerKey(100)));

        assertTrue(report.successful());
        assertEquals(1, executor.pending());
        assertTrue(scene.allianceOf(10001L).isEmpty());
        executor.runNext();
        assertEquals(100, scene.allianceOf(10001L).orElseThrow().allianceId());
        assertFalse(scene.allianceOf(10001L).orElseThrow().stale());
    }

    @Test
    void repairReportsInvalidOwnerKey() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SceneAllianceAwarenessAgent scene = new SceneAllianceAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-alliance")
        );
        AllianceSnapshotRepairer repairer = new AllianceSnapshotRepairer(
                allianceId -> Optional.empty(),
                scene
        );

        SnapshotRepairReport report = repairer.repair(Set.of("bad:100"));

        assertEquals(1, report.invalidOwnerKeys());
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        int pending() {
            return commands.size();
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
