package com.commonbattle.example.auto;

import com.commonbattle.core.AttributeComponent;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Entity;
import com.commonbattle.core.FactionComponent;
import com.commonbattle.core.HealthComponent;

/**
 * Small factory used by tests and the runnable example to assemble an auto battle.
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
        return BattleContext.builder()
                .state(state)
                .ruleSet(new AutoBattleRuleSet())
                .build();
    }
}
