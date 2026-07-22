package com.commonbattle.example.auto;

import com.commonbattle.core.AttributeComponent;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Entity;
import com.commonbattle.core.FactionComponent;
import com.commonbattle.core.GainRageOnAttackTrigger;
import com.commonbattle.core.HealthComponent;
import com.commonbattle.core.TriggerTiming;

/**
 * 自动战斗示例工厂，用于测试和可运行示例快速组装战斗。
 */
public final class AutoBattleExampleFactory {
    private AutoBattleExampleFactory() {
    }

    public static Entity createFighter(BattleState state, String type, int hp, int attack, String faction) {
        Entity fighter = state.createEntity(type);
        fighter.add(new HealthComponent(hp))
                .add(new AttributeComponent().set("attack", attack))
                .add(new FactionComponent(faction));
        return fighter;
    }

    public static BattleContext createBattle(BattleState state) {
        BattleContext battle = BattleContext.builder()
                .state(state)
                .ruleSet(new AutoBattleRuleSet())
                .build();
        battle.triggerSystem().register(TriggerTiming.AFTER_DAMAGE, new GainRageOnAttackTrigger());
        return battle;
    }
}
