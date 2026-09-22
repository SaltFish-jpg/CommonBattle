package com.commonbattle.battle;




import com.commonbattle.battle.command.AttackCommand;
import com.commonbattle.battle.command.Command;
import com.commonbattle.battle.component.AttributeComponent;
import com.commonbattle.battle.component.FactionComponent;
import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.component.RageEnergyComponent;
import com.commonbattle.battle.component.RageSkillComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.DamageContext;
import com.commonbattle.battle.effect.DealDamageEffect;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.event.Event;
import com.commonbattle.battle.event.EventBus;
import com.commonbattle.battle.event.SkillCastEvent;
import com.commonbattle.battle.event.UnitDamagedEvent;
import com.commonbattle.battle.rule.BasicRuleSet;
import com.commonbattle.battle.rule.RuleSet;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.trigger.GainRageOnAttackTrigger;
import com.commonbattle.battle.trigger.Trigger;
import com.commonbattle.battle.trigger.TriggerSystem;
import com.commonbattle.battle.trigger.TriggerTiming;
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

    @Test
    void attackDamageGainsRageAndFullRageQueuesSkill() {
        BattleState state = new BattleState();
        Entity attacker = state.createEntity("hero");
        attacker.add(new HealthComponent(100));
        attacker.add(new AttributeComponent().set("attack", 25));
        attacker.add(new RageEnergyComponent(100, 80, 30, 10));

        Entity defender = state.createEntity("monster");
        defender.add(new HealthComponent(100));
        defender.add(new RageEnergyComponent(100, 0, 10, 15));
        attacker.add(new RageSkillComponent(
                "ultimate_slash",
                List.of(new DealDamageEffect(attacker.id(), defender.id(), 40))
        ));

        List<Event> events = new ArrayList<>();
        BattleContext context = BattleContext.builder()
                .state(state)
                .ruleSet(new BasicRuleSet())
                .build();
        context.eventBus().subscribe(events::add);
        context.triggerSystem().register(TriggerTiming.AFTER_DAMAGE, new GainRageOnAttackTrigger());

        context.submit(new AttackCommand(attacker.id(), defender.id()));
        context.runUntilIdle();

        assertEquals(35, defender.require(HealthComponent.class).current());
        assertEquals(0, attacker.require(RageEnergyComponent.class).current());
        assertEquals(15, defender.require(RageEnergyComponent.class).current());
        assertTrue(events.stream().anyMatch(event -> event instanceof SkillCastEvent skill
                && skill.skillId().equals("ultimate_slash")));
        assertTrue(context.log().entries().stream().anyMatch(entry -> entry.type().equals("effect.rage_skill_ready")));
    }
}
