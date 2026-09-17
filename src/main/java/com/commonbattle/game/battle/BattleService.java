package com.commonbattle.game.battle;

import com.commonbattle.core.AttackCommand;
import com.commonbattle.core.AttributeComponent;
import com.commonbattle.core.BasicRuleSet;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Entity;
import com.commonbattle.core.FactionComponent;
import com.commonbattle.core.HealthComponent;
import com.commonbattle.game.activity.ActivityAccessContext;
import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.activity.PlayerActivities;
import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.PlayerBag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 玩家 PVE 战斗结算服务。
 * 调用方应在玩家 Actor 邮箱内执行它，胜利奖励、活动进度和玩家状态修改会在同一条消息中串行完成。
 */
public final class BattleService {
    private final BattleStageCatalog catalog;
    private final BagService bagService;

    public BattleService(BattleStageCatalog catalog, BagService bagService) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.bagService = Objects.requireNonNull(bagService, "bagService");
    }

    public BattleService(BattleStageCatalog catalog, BagService bagService, ActivityService ignored) {
        this(catalog, bagService);
    }

    public BattleStageDefinition requireStage(String stageId) {
        return catalog.require(stageId);
    }

    public BattleSettlementResult clear(
            PlayerBag bag,
            PlayerBattleState battleState,
            java.time.Instant now,
            String settlementId,
            String stageId
    ) {
        Objects.requireNonNull(bag, "bag");
        Objects.requireNonNull(battleState, "battleState");
        Objects.requireNonNull(now, "now");
        settlementId = Objects.requireNonNullElse(settlementId, "");
        BattleStageDefinition definition = catalog.require(stageId);
        var replayed = battleState.replay(settlementId, definition.stageId());
        if (replayed.isPresent()) {
            return replayed.orElseThrow();
        }
        BattleState state = new BattleState();
        Entity player = createUnit(state, "player", "player", definition.playerHp(), definition.playerAttack());
        Entity enemy = createUnit(state, "stage." + definition.stageId(), "enemy",
                definition.enemyHp(), definition.enemyAttack());
        BattleContext battle = BattleContext.builder()
                .state(state)
                .ruleSet(new BasicRuleSet())
                .build();

        int rounds = runDuel(definition, battle, player, enemy);
        HealthComponent playerHealth = player.require(HealthComponent.class);
        HealthComponent enemyHealth = enemy.require(HealthComponent.class);
        BattleSettlementStatus status = settleStatus(playerHealth, enemyHealth);
        BagResult rewardResult = new BagResult(List.of());
        String progressActivityId = "";
        int progressDelta = 0;
        int stars = 0;
        boolean firstClear = false;
        int clearCount = battleState.findProgress(definition.stageId())
                .map(BattleStageProgress::clearCount)
                .orElse(0);
        if (status == BattleSettlementStatus.VICTORY) {
            BattleStageProgress progress = battleState.progress(definition.stageId());
            stars = calculateStars(definition, rounds, playerHealth.current());
            firstClear = progress.recordVictory(stars, now);
            clearCount = progress.clearCount();
            // 战斗结算边界：只有确认胜利后，才允许更新关卡进度和发放背包奖励；活动等派生状态由玩家事件处理器推进。
            rewardResult = grantVictoryRewards(bag, definition, firstClear);
            if (!definition.progressActivityId().isBlank() && definition.progressDelta() > 0) {
                progressActivityId = definition.progressActivityId();
                progressDelta = definition.progressDelta();
            }
        }
        BattleSettlementResult result = new BattleSettlementResult(
                Objects.requireNonNullElse(settlementId, ""),
                definition.stageId(),
                status,
                rounds,
                playerHealth.current(),
                enemyHealth.current(),
                rewardResult,
                progressActivityId,
                progressDelta,
                stars,
                firstClear,
                clearCount,
                battle.log().entries(),
                false,
                false
        );
        battleState.record(result);
        return result;
    }

    public BattleSettlementResult sweep(
            PlayerBag bag,
            PlayerBattleState battleState,
            java.time.Instant now,
            String settlementId,
            String stageId
    ) {
        Objects.requireNonNull(bag, "bag");
        Objects.requireNonNull(battleState, "battleState");
        Objects.requireNonNull(now, "now");
        settlementId = Objects.requireNonNullElse(settlementId, "");
        BattleStageDefinition definition = catalog.require(stageId);
        var replayed = battleState.replay(settlementId, definition.stageId());
        if (replayed.isPresent()) {
            return replayed.orElseThrow();
        }
        BattleStageProgress progress = battleState.progress(definition.stageId());
        if (!definition.sweepable()) {
            throw new IllegalStateException("battle stage is not sweepable: " + definition.stageId());
        }
        if (progress.bestStars() < definition.sweepRequiredStars()) {
            throw new IllegalStateException("battle stage sweep stars not reached: " + definition.stageId());
        }
        int stars = progress.bestStars();
        progress.recordVictory(stars, now);
        BagResult rewardResult = bagService.grant(bag, definition.victoryReward());
        String progressActivityId = "";
        int progressDelta = 0;
        if (!definition.progressActivityId().isBlank() && definition.progressDelta() > 0) {
            progressActivityId = definition.progressActivityId();
            progressDelta = definition.progressDelta();
        }
        BattleSettlementResult result = new BattleSettlementResult(
                settlementId,
                definition.stageId(),
                BattleSettlementStatus.VICTORY,
                0,
                definition.playerHp(),
                0,
                rewardResult,
                progressActivityId,
                progressDelta,
                stars,
                false,
                progress.clearCount(),
                List.of(),
                true,
                false
        );
        battleState.record(result);
        return result;
    }

    public BattleSettlementResult clear(
            PlayerBag bag,
            PlayerBattleState battleState,
            PlayerActivities activities,
            ActivityAccessContext access,
            String settlementId,
            String stageId
    ) {
        Objects.requireNonNull(activities, "activities");
        Objects.requireNonNull(access, "access");
        return clear(bag, battleState, access.now(), settlementId, stageId);
    }

    public BattleSettlementResult sweep(
            PlayerBag bag,
            PlayerBattleState battleState,
            PlayerActivities activities,
            ActivityAccessContext access,
            String settlementId,
            String stageId
    ) {
        Objects.requireNonNull(activities, "activities");
        Objects.requireNonNull(access, "access");
        return sweep(bag, battleState, access.now(), settlementId, stageId);
    }

    public BattleSettlementResult clear(
            PlayerBag bag,
            PlayerActivities activities,
            ActivityAccessContext access,
            String stageId
    ) {
        return clear(bag, new PlayerBattleState(), access.now(), "", stageId);
    }

    private Entity createUnit(BattleState state, String type, String faction, int hp, int attack) {
        return state.createEntity(type)
                .add(new HealthComponent(hp))
                .add(new AttributeComponent().set("attack", attack))
                .add(new FactionComponent(faction));
    }

    private int runDuel(BattleStageDefinition definition, BattleContext battle, Entity player, Entity enemy) {
        int rounds = 0;
        while (player.require(HealthComponent.class).alive()
                && enemy.require(HealthComponent.class).alive()
                && rounds < definition.maxRounds()) {
            rounds++;
            battle.submit(new AttackCommand(player.id(), enemy.id()));
            battle.runUntilIdle();
            if (enemy.require(HealthComponent.class).alive()) {
                battle.submit(new AttackCommand(enemy.id(), player.id()));
                battle.runUntilIdle();
            }
        }
        return rounds;
    }

    private BattleSettlementStatus settleStatus(HealthComponent player, HealthComponent enemy) {
        if (!enemy.alive()) {
            return BattleSettlementStatus.VICTORY;
        }
        if (!player.alive()) {
            return BattleSettlementStatus.DEFEAT;
        }
        return BattleSettlementStatus.DRAW;
    }

    private int calculateStars(BattleStageDefinition definition, int rounds, int playerHp) {
        if (rounds <= (definition.maxRounds() + 1) / 2 && playerHp * 100 >= definition.playerHp() * 70) {
            return 3;
        }
        if (playerHp * 100 >= definition.playerHp() * 30) {
            return 2;
        }
        return 1;
    }

    private BagResult grantVictoryRewards(PlayerBag bag, BattleStageDefinition definition, boolean firstClear) {
        List<com.commonbattle.game.bag.BagChange> changes = new ArrayList<>();
        changes.addAll(bagService.grant(bag, definition.victoryReward()).changes());
        if (firstClear) {
            changes.addAll(bagService.grant(bag, definition.firstClearReward()).changes());
        }
        return new BagResult(changes);
    }
}
