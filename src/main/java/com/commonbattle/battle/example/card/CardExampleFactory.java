package com.commonbattle.battle.example.card;




import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.rule.RuleSet;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
/**
 * 卡牌战斗示例工厂，用于测试和可运行示例快速组装战斗。
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
