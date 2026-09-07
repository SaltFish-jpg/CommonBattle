package com.commonbattle.game.player;

import com.commonbattle.game.activity.ActivityProgressSnapshot;
import com.commonbattle.game.activity.PlayerActivitiesSnapshot;
import com.commonbattle.game.achievement.AchievementProgressSnapshot;
import com.commonbattle.game.achievement.PlayerAchievementsSnapshot;
import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.battle.BattleSettlementSnapshot;
import com.commonbattle.game.battle.BattleSettlementStatus;
import com.commonbattle.game.battle.BattleStageProgressSnapshot;
import com.commonbattle.game.battle.PlayerBattleSnapshot;
import com.commonbattle.game.growth.GrowthSnapshot;
import com.commonbattle.game.shop.PlayerShopSnapshot;
import com.commonbattle.game.task.PlayerTasksSnapshot;
import com.commonbattle.game.task.TaskProgressSnapshot;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SerializedPlayerStateRepositoryTest {
    private final ProtostuffPlayerStateSnapshotSerializer serializer = new ProtostuffPlayerStateSnapshotSerializer();

    @Test
    void protostuffSerializerRoundTripsFullPlayerStateSnapshot() {
        PlayerStateSnapshot snapshot = snapshot();

        PlayerStateSnapshot decoded = serializer.deserialize(serializer.serialize(snapshot));

        assertEquals(snapshot, decoded);
    }

    @Test
    void serializedRepositoryCanRecoverAfterRebuild() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        SerializedPlayerStateRepository first = new SerializedPlayerStateRepository(bytes, serializer);
        first.save(10001L, snapshot());

        SerializedPlayerStateRepository rebuilt = new SerializedPlayerStateRepository(bytes, serializer);

        assertEquals(snapshot(), rebuilt.load(10001L).orElseThrow());
    }

    @Test
    void repositoryRejectsMismatchedPlayerKey() {
        SerializedPlayerStateRepository repository = new SerializedPlayerStateRepository(
                new InMemoryAtomicBytesStore(),
                serializer
        );

        assertThrows(IllegalArgumentException.class, () -> repository.save(10002L, snapshot()));
    }

    private PlayerStateSnapshot snapshot() {
        Instant createdAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant savedAt = Instant.parse("2026-09-01T00:00:00Z");
        return new PlayerStateSnapshot(
                10001L,
                createdAt,
                new BagSnapshot(Map.of("gold", 100, "exp_potion", 2)),
                new PlayerActivitiesSnapshot(Map.of("battle-win-1", new ActivityProgressSnapshot(1, true))),
                new GrowthSnapshot(2, 20),
                new PlayerShopSnapshot(
                        Map.of("growth_pack", 1),
                        Map.of("growth_pack@2026-09-01", 1)
                ),
                new PlayerBattleSnapshot(
                        Map.of("settle-1", new BattleSettlementSnapshot(
                                "settle-1",
                                "forest-1",
                                BattleSettlementStatus.VICTORY,
                                3,
                                90,
                                0,
                                new BagResult(List.of(new BagChange("gold", 0, 30))),
                                "battle-win-1",
                                1,
                                3,
                                true,
                                1,
                                false
                        )),
                        Map.of("forest-1", new BattleStageProgressSnapshot(
                                "forest-1",
                                1,
                                3,
                                savedAt,
                                savedAt
                        ))
                ),
                new PlayerTasksSnapshot(Map.of("task-clear-forest", new TaskProgressSnapshot(1, true))),
                new PlayerAchievementsSnapshot(Map.of(
                        "achievement-clear-forest",
                        new AchievementProgressSnapshot(1, false)
                )),
                8,
                9,
                savedAt
        );
    }
}
