package com.commonbattle.game.player.event;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.achievement.PlayerAchievementsSnapshot;
import com.commonbattle.game.activity.PlayerActivitiesSnapshot;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.battle.BattleStageProgressSnapshot;
import com.commonbattle.game.battle.PlayerBattleSnapshot;
import com.commonbattle.game.growth.GrowthSnapshot;
import com.commonbattle.game.player.InMemoryPlayerStateRepository;
import com.commonbattle.game.player.PlayerStateSnapshot;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.shop.PlayerShopSnapshot;
import com.commonbattle.game.snapshot.SnapshotRepairStatus;
import com.commonbattle.game.task.PlayerTasksSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenePlayerDomainSnapshotRepairerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void rebuildsSceneProjectionFromPlayerStateSnapshotThroughMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ScenePlayerDomainEventAgent scene = new ScenePlayerDomainEventAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-domain-repair")
        );
        InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();
        repository.save(10001L, state(10001L, 5, Map.of(
                "forest-1", 2,
                "cave-1", 1
        )));
        scene.enter(10001L);
        executor.runNext();
        ScenePlayerDomainSnapshotRepairer repairer = new ScenePlayerDomainSnapshotRepairer(
                new PlayerStateDomainProjectionSnapshotReader(repository),
                scene
        );

        var report = repairer.repair(Set.of(PlayerDomainVersionedEvent.ownerKey(10001L)));

        assertEquals(1, report.refreshed());
        assertTrue(scene.stageClears(10001L).isEmpty());

        executor.runNext();

        assertEquals(3, scene.stageClears(10001L).orElseThrow());
        assertEquals(5, scene.revisionOf(10001L));
        assertFalse(scene.stale(10001L));
    }

    @Test
    void reportsInvalidAndMissingOwners() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ScenePlayerDomainEventAgent scene = new ScenePlayerDomainEventAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-domain-repair")
        );
        ScenePlayerDomainSnapshotRepairer repairer = new ScenePlayerDomainSnapshotRepairer(
                new PlayerStateDomainProjectionSnapshotReader(new InMemoryPlayerStateRepository()),
                scene
        );

        var report = repairer.repair(Set.of("bad", PlayerDomainVersionedEvent.ownerKey(10001L)));

        assertEquals(1, report.invalidOwnerKeys());
        assertEquals(1, report.missing());
        assertTrue(report.results().stream().anyMatch(result ->
                result.status() == SnapshotRepairStatus.INVALID_OWNER_KEY));
    }

    private static PlayerStateSnapshot state(long playerId, long eventRevision, Map<String, Integer> clears) {
        Map<String, BattleStageProgressSnapshot> stages = clears.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new BattleStageProgressSnapshot(entry.getKey(), entry.getValue(), 3, NOW, NOW)
                ));
        return new PlayerStateSnapshot(
                playerId,
                NOW,
                new BagSnapshot(Map.of()),
                new PlayerActivitiesSnapshot(Map.of()),
                new GrowthSnapshot(20, 0),
                PlayerShopSnapshot.empty(),
                new PlayerBattleSnapshot(Map.of(), stages),
                PlayerTasksSnapshot.empty(),
                PlayerAchievementsSnapshot.empty(),
                eventRevision,
                1,
                NOW
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
