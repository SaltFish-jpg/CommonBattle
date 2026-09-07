package com.commonbattle.game.battle;

import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.bag.Reward;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void victoryGrantsRewardAndReportsActivityProgressEventData() {
        Fixture fixture = Fixture.create();
        PlayerBag bag = new PlayerBag();
        PlayerBattleState battleState = new PlayerBattleState();

        BattleSettlementResult result = fixture.service().clear(
                bag,
                battleState,
                NOW,
                "",
                "forest-1"
        );

        assertTrue(result.victory());
        assertEquals(BattleSettlementStatus.VICTORY, result.status());
        assertEquals(2, result.rounds());
        assertEquals(3, result.stars());
        assertTrue(result.firstClear());
        assertEquals(1, result.clearCount());
        assertEquals(30, bag.count("gold"));
        assertEquals(1, bag.count("exp_potion"));
        assertEquals(5, bag.count("gem"));
        assertEquals("battle-win-1", result.progressActivityId());
        assertEquals(1, result.progressDelta());
        assertFalse(result.log().isEmpty());
    }

    @Test
    void sameSettlementIdReplaysWithoutGrantingRewardTwice() {
        Fixture fixture = Fixture.create();
        PlayerBag bag = new PlayerBag();
        PlayerBattleState battleState = new PlayerBattleState();

        BattleSettlementResult first = fixture.service().clear(
                bag,
                battleState,
                NOW,
                "settle-10001-1",
                "forest-1"
        );
        BattleSettlementResult replay = fixture.service().clear(
                bag,
                battleState,
                NOW,
                "settle-10001-1",
                "forest-1"
        );

        assertTrue(first.victory());
        assertTrue(replay.replayed());
        assertEquals(30, bag.count("gold"));
        assertEquals(1, bag.count("exp_potion"));
        assertEquals(5, bag.count("gem"));
        assertEquals(first.rewardResult(), replay.rewardResult());
    }

    @Test
    void firstClearRewardIsGrantedOnlyOnceAcrossDifferentSettlements() {
        Fixture fixture = Fixture.create();
        PlayerBag bag = new PlayerBag();
        PlayerBattleState battleState = new PlayerBattleState();

        BattleSettlementResult first = fixture.service().clear(
                bag,
                battleState,
                NOW,
                "settle-10001-1",
                "forest-1"
        );
        BattleSettlementResult second = fixture.service().clear(
                bag,
                battleState,
                NOW,
                "settle-10001-2",
                "forest-1"
        );

        assertTrue(first.firstClear());
        assertFalse(second.firstClear());
        assertEquals(2, second.clearCount());
        assertEquals(60, bag.count("gold"));
        assertEquals(2, bag.count("exp_potion"));
        assertEquals(5, bag.count("gem"));
    }

    @Test
    void sweepRequiresConfiguredStarsAndUsesSettlementIdempotency() {
        Fixture fixture = Fixture.create();
        PlayerBag bag = new PlayerBag();
        PlayerBattleState battleState = new PlayerBattleState();

        fixture.service().clear(
                bag,
                battleState,
                NOW,
                "settle-10001-1",
                "forest-1"
        );
        BattleSettlementResult sweep = fixture.service().sweep(
                bag,
                battleState,
                NOW,
                "sweep-10001-1",
                "forest-1"
        );
        BattleSettlementResult replay = fixture.service().sweep(
                bag,
                battleState,
                NOW,
                "sweep-10001-1",
                "forest-1"
        );

        assertTrue(sweep.swept());
        assertTrue(replay.replayed());
        assertEquals(2, sweep.clearCount());
        assertEquals(60, bag.count("gold"));
        assertEquals(2, bag.count("exp_potion"));
        assertEquals(5, bag.count("gem"));
    }

    @Test
    void sweepBeforeRequiredStarsIsRejected() {
        Fixture fixture = Fixture.create();
        PlayerBag bag = new PlayerBag();
        PlayerBattleState battleState = new PlayerBattleState();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> fixture.service().sweep(
                bag,
                battleState,
                NOW,
                "sweep-10001-1",
                "forest-1"
        ));
    }

    @Test
    void settlementIdCannotBeReusedForAnotherStage() {
        Fixture fixture = Fixture.create();
        PlayerBag bag = new PlayerBag();
        PlayerBattleState battleState = new PlayerBattleState();

        fixture.service().clear(
                bag,
                battleState,
                NOW,
                "settle-10001-1",
                "forest-1"
        );

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> fixture.service().clear(
                bag,
                battleState,
                NOW,
                "settle-10001-1",
                "hard-1"
        ));
    }

    @Test
    void defeatDoesNotMutateBagOrActivityProgress() {
        Fixture fixture = Fixture.create();
        PlayerBag bag = new PlayerBag();
        PlayerBattleState battleState = new PlayerBattleState();

        BattleSettlementResult result = fixture.service().clear(
                bag,
                battleState,
                NOW,
                "",
                "hard-1"
        );

        assertEquals(BattleSettlementStatus.DEFEAT, result.status());
        assertEquals(0, bag.count("gold"));
        assertEquals(0, bag.count("exp_potion"));
        assertEquals(0, battleState.findProgress("hard-1").map(BattleStageProgress::clearCount).orElse(0));
    }

    private record Fixture(BattleService service) {
        private static Fixture create() {
            ItemCatalog items = new ItemCatalog();
            items.register(new ItemDefinition("gold", "currency", 999999));
            items.register(new ItemDefinition("exp_potion", "growth", 999));
            items.register(new ItemDefinition("gem", "currency", 999999));
            BagService bagService = new BagService(items);
            BattleStageCatalog battles = new BattleStageCatalog();
            battles.register(new BattleStageDefinition(
                    "forest-1",
                    100,
                    40,
                    70,
                    8,
                    5,
                    Reward.of(new ItemStack("gold", 30), new ItemStack("exp_potion", 1)),
                    "battle-win-1",
                    1,
                    Reward.of(new ItemStack("gem", 5)),
                    3
            ));
            battles.register(new BattleStageDefinition(
                    "hard-1",
                    30,
                    5,
                    100,
                    40,
                    5,
                    Reward.of(new ItemStack("gold", 30)),
                    "battle-win-1",
                    1
            ));
            return new Fixture(new BattleService(battles, bagService));
        }
    }
}
