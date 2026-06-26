package com.commonbattle.example.card;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Entity;
import com.commonbattle.core.HealthComponent;

/**
 * Small factory used by tests and the runnable example to assemble a card battle.
 */
public final class CardExampleFactory {
    private CardExampleFactory() {
    }

    public static Entity createPlayer(BattleState state, int mana) {
        Entity player = state.createEntity("player");
        player.add(new ManaComponent(mana));
        return player;
    }

    public static Entity createDamageCard(BattleState state, String type, int cost, int damage) {
        Entity card = state.createEntity(type);
        card.add(new ZoneComponent(Zone.HAND))
                .add(new CostComponent(cost))
                .add(new CardDamageComponent(damage));
        return card;
    }

    public static Entity createTarget(BattleState state, int hp) {
        Entity target = state.createEntity("target");
        target.add(new HealthComponent(hp));
        return target;
    }

    public static BattleContext createBattle(BattleState state) {
        return BattleContext.builder()
                .state(state)
                .ruleSet(new CardRuleSet())
                .build();
    }
}
