package com.commonbattle.game.profile;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.game.snapshot.SnapshotRepairStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneProfileSnapshotRepairerTest {
    @Test
    void refreshesSceneProfileThroughMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryProfileSnapshotRepository repository = new InMemoryProfileSnapshotRepository();
        LocalProfileCache cache = new LocalProfileCache();
        SceneProfileAwarenessAgent scene = new SceneProfileAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-profile"),
                new ProfileRuntime(cache, ProfileInterestControl.noop(), repository)
        );
        repository.save(snapshot(10001L, 3, "avatar_3"));
        scene.enter(10001L);
        executor.runNext();
        SceneProfileSnapshotRepairer repairer = new SceneProfileSnapshotRepairer(repository, scene);

        var report = repairer.repair(Set.of("profile:10001"));

        assertEquals(1, report.refreshed());
        assertTrue(scene.profileOf(10001L).isEmpty());
        executor.runNext();
        assertEquals("avatar_3", scene.profileOf(10001L).orElseThrow().snapshot().appearance().avatar());
        assertEquals(3, scene.profileRuntime().revisionOf(10001L));
    }

    @Test
    void reportsInvalidAndMissingOwners() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SceneProfileAwarenessAgent scene = new SceneProfileAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-profile"),
                ProfileInterestControl.noop()
        );
        SceneProfileSnapshotRepairer repairer = new SceneProfileSnapshotRepairer(
                new InMemoryProfileSnapshotRepository(),
                scene
        );

        var report = repairer.repair(Set.of("bad", "profile:10001"));

        assertEquals(1, report.invalidOwnerKeys());
        assertEquals(1, report.missing());
        assertTrue(report.results().stream().anyMatch(result ->
                result.status() == SnapshotRepairStatus.INVALID_OWNER_KEY));
    }

    private static PlayerProfileSnapshot snapshot(long playerId, long revision, String avatar) {
        return new PlayerProfileSnapshot(
                playerId,
                "hero",
                20,
                new AppearanceSummary(avatar, "frame_1", "costume_1"),
                AllianceBrief.none(),
                new FriendBrief(3, 1),
                revision,
                Instant.parse("2026-09-01T00:00:00Z")
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
