package com.commonbattle.battle.example;




import com.commonbattle.battle.command.AttackCommand;
import com.commonbattle.battle.component.AttributeComponent;
import com.commonbattle.battle.component.FactionComponent;
import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.DamageContext;
import com.commonbattle.battle.event.Event;
import com.commonbattle.battle.event.EventBus;
import com.commonbattle.battle.log.BattleLogEntry;
import com.commonbattle.battle.rule.BasicRuleSet;
import com.commonbattle.battle.rule.RuleSet;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.trigger.Trigger;
import com.commonbattle.battle.trigger.TriggerSystem;
import com.commonbattle.battle.trigger.TriggerTiming;
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
