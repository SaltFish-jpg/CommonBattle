package com.commonbattle.example;

import com.commonbattle.core.AttackCommand;
import com.commonbattle.core.AttributeComponent;
import com.commonbattle.core.BasicRuleSet;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleLogEntry;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.DamageContext;
import com.commonbattle.core.Entity;
import com.commonbattle.core.FactionComponent;
import com.commonbattle.core.HealthComponent;
import com.commonbattle.core.TriggerTiming;

public final class SimpleBattleExample {
    private SimpleBattleExample() {
    }

    public static void main(String[] args) {
        BattleState state = new BattleState();
        Entity hero = state.createEntity("hero");
        hero.add(new HealthComponent(100))
                .add(new AttributeComponent().set("attack", 30))
                .add(new FactionComponent("player"));

        Entity monster = state.createEntity("monster");
        monster.add(new HealthComponent(90))
                .add(new FactionComponent("enemy"));

        BattleContext battle = BattleContext.builder()
                .state(state)
                .ruleSet(new BasicRuleSet())
                .build();

        battle.triggerSystem().register(TriggerTiming.BEFORE_DAMAGE, context -> {
            DamageContext damage = context.require(DamageContext.class);
            damage.increaseAmount(10);
            context.log().add("trigger.before_damage", "rage aura added 10 damage");
        });
        battle.eventBus().subscribe(event -> battle.log().add("event", event.toString()));

        battle.submit(new AttackCommand(hero.id(), monster.id()));
        battle.runUntilIdle();

        for (BattleLogEntry entry : battle.log().entries()) {
            System.out.printf("%02d %-22s %s%n", entry.sequence(), entry.type(), entry.message());
        }
    }
}
