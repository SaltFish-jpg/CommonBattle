package com.commonbattle.example.turn;

import com.commonbattle.core.AttackCommand;
import com.commonbattle.core.AttributeComponent;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Entity;
import com.commonbattle.core.EntityId;
import com.commonbattle.core.FactionComponent;
import com.commonbattle.core.HealthComponent;

import java.util.List;

/**
 * 回合制战斗示例工厂，用于测试和可运行示例快速组装战斗。
 */
public final class TurnBasedExampleFactory {
    private TurnBasedExampleFactory() {
    }

    public static Entity createUnit(BattleState state, String type, int hp, int attack, String faction) {
        Entity unit = state.createEntity(type);
        unit.add(new HealthComponent(hp))
                .add(new AttributeComponent().set("attack", attack))
                .add(new FactionComponent(faction));
        return unit;
    }

    public static BattleContext createBattle(BattleState state, EntityId first, EntityId second) {
        state.putGlobal(new TurnComponent(List.of(first, second)));
        return BattleContext.builder()
                .state(state)
                .ruleSet(new TurnRuleSet())
                .build();
    }

    public static AttackCommand attack(EntityId attacker, EntityId target) {
        return new AttackCommand(attacker, target);
    }
}
