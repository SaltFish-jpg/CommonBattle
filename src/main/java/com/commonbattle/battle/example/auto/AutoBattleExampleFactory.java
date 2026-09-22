package com.commonbattle.battle.example.auto;




import com.commonbattle.battle.component.AttributeComponent;
import com.commonbattle.battle.component.FactionComponent;
import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.rule.RuleSet;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.trigger.GainRageOnAttackTrigger;
import com.commonbattle.battle.trigger.TriggerSystem;
import com.commonbattle.battle.trigger.TriggerTiming;
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
