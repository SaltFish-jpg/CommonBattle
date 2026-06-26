package com.commonbattle.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleFlowTest {
    @Test
    void attackCommandDealsDamagePublishesEventsRunsTriggersAndWritesLog() {
        BattleState state = new BattleState();
        Entity attacker = state.createEntity("knight");
        attacker.add(new HealthComponent(100));
        attacker.add(new AttributeComponent().set("attack", 25));
        attacker.add(new FactionComponent("blue"));

        Entity defender = state.createEntity("orc");
        defender.add(new HealthComponent(80));
        defender.add(new FactionComponent("red"));

        List<Event> events = new ArrayList<>();
        BattleContext context = BattleContext.builder()
                .state(state)
                .ruleSet(new BasicRuleSet())
                .build();
        context.eventBus().subscribe(events::add);
        context.triggerSystem().register(TriggerTiming.BEFORE_DAMAGE, battle -> {
            DamageContext damage = battle.require(DamageContext.class);
            damage.increaseAmount(5);
            battle.log().add("trigger.before_damage", "flat bonus damage applied");
        });

        context.submit(new AttackCommand(attacker.id(), defender.id()));
        context.runUntilIdle();

        assertEquals(50, defender.require(HealthComponent.class).current());
        assertTrue(events.stream().anyMatch(UnitDamagedEvent.class::isInstance));
        assertTrue(context.log().entries().stream().anyMatch(entry -> entry.type().equals("trigger.before_damage")));
        assertTrue(context.log().entries().stream().anyMatch(entry -> entry.type().equals("effect.damage")));
        assertTrue(context.log().entries().stream().anyMatch(entry -> entry.type().equals("command.completed")));
    }
}
