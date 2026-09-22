package com.commonbattle.battle;




import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.buff.StatusEffect;
import com.commonbattle.battle.command.ApplyBuffCommand;
import com.commonbattle.battle.command.AttackCommand;
import com.commonbattle.battle.command.CastSkillCommand;
import com.commonbattle.battle.component.AttributeComponent;
import com.commonbattle.battle.component.BuffComponent;
import com.commonbattle.battle.component.CounterAttackComponent;
import com.commonbattle.battle.component.FactionComponent;
import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.component.PositionComponent;
import com.commonbattle.battle.component.ReflectDamageComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.AreaDamageEffect;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.effect.SummonEffect;
import com.commonbattle.battle.effect.TeleportEffect;
import com.commonbattle.battle.event.EntityTeleportedEvent;
import com.commonbattle.battle.event.Event;
import com.commonbattle.battle.event.EventBus;
import com.commonbattle.battle.rule.BasicRuleSet;
import com.commonbattle.battle.rule.RuleSet;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.targeting.TargetSelector;
import com.commonbattle.battle.trigger.CounterAttackTrigger;
import com.commonbattle.battle.trigger.ReflectDamageTrigger;
import com.commonbattle.battle.trigger.TriggerSystem;
import com.commonbattle.battle.trigger.TriggerTiming;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedEffectsTest {
    @Test
    void attributeBuffIncreasesAttackDamage() {
        BattleState state = new BattleState();
        Entity attacker = unit(state, "mage", 100, 10, "blue");
        Entity target = unit(state, "dummy", 60, 0, "red");
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();

        battle.submit(new ApplyBuffCommand(attacker.id(), Buff.attribute("battle_cry", "attack", 5, 2)));
        battle.submit(new AttackCommand(attacker.id(), target.id()));
        battle.runUntilIdle();

        assertEquals(45, target.require(HealthComponent.class).current());
        assertTrue(attacker.require(BuffComponent.class).has("battle_cry"));
    }

    @Test
    void stunnedOrFrozenUnitCannotAct() {
        BattleState state = new BattleState();
        Entity attacker = unit(state, "rogue", 100, 10, "blue");
        Entity target = unit(state, "dummy", 60, 0, "red");
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();

        battle.submit(new ApplyBuffCommand(attacker.id(), Buff.status("stun", StatusEffect.STUNNED, 1)));
        battle.runUntilIdle();

        assertThrows(IllegalStateException.class, () -> {
            battle.submit(new AttackCommand(attacker.id(), target.id()));
            battle.runUntilIdle();
        });
        assertEquals(60, target.require(HealthComponent.class).current());
    }

    @Test
    void areaDamageSkillHitsAllEnemies() {
        BattleState state = new BattleState();
        Entity caster = unit(state, "wizard", 100, 0, "blue");
        Entity enemyOne = unit(state, "goblin", 30, 0, "red");
        Entity enemyTwo = unit(state, "orc", 40, 0, "red");
        Entity ally = unit(state, "soldier", 50, 0, "blue");
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();

        battle.submit(new CastSkillCommand(
                caster.id(),
                "flame_storm",
                List.of(new AreaDamageEffect(caster.id(), TargetSelector.enemiesOf("blue"), 12))
        ));
        battle.runUntilIdle();

        assertEquals(18, enemyOne.require(HealthComponent.class).current());
        assertEquals(28, enemyTwo.require(HealthComponent.class).current());
        assertEquals(50, ally.require(HealthComponent.class).current());
    }

    @Test
    void summonEffectCreatesEntityWithConfiguredComponents() {
        BattleState state = new BattleState();
        Entity summoner = unit(state, "summoner", 100, 0, "blue");
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();

        battle.submit(new CastSkillCommand(
                summoner.id(),
                "summon_guard",
                List.of(new SummonEffect(summoner.id(), "guard", entity -> entity
                        .add(new HealthComponent(30))
                        .add(new AttributeComponent().set("attack", 6))
                        .add(new FactionComponent("blue"))))
        ));
        battle.runUntilIdle();

        Entity summoned = state.entities().stream()
                .filter(entity -> entity.type().equals("guard"))
                .findFirst()
                .orElseThrow();
        assertEquals(30, summoned.require(HealthComponent.class).current());
        assertEquals("blue", summoned.require(FactionComponent.class).faction());
        assertTrue(battle.log().entries().stream().anyMatch(entry -> entry.type().equals("effect.summon")));
    }

    @Test
    void teleportEffectMovesPositionAndPublishesEvent() {
        BattleState state = new BattleState();
        Entity unit = unit(state, "blink_mage", 100, 0, "blue");
        unit.add(new PositionComponent(1, 2));
        List<Event> events = new ArrayList<>();
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();
        battle.eventBus().subscribe(events::add);

        battle.submit(new CastSkillCommand(unit.id(), "blink", List.of(new TeleportEffect(unit.id(), 5, 7))));
        battle.runUntilIdle();

        PositionComponent position = unit.require(PositionComponent.class);
        assertEquals(5, position.x());
        assertEquals(7, position.y());
        assertTrue(events.stream().anyMatch(EntityTeleportedEvent.class::isInstance));
    }

    @Test
    void reflectDamageReturnsDamageWithoutReflectLoop() {
        BattleState state = new BattleState();
        Entity attacker = unit(state, "swordsman", 100, 20, "blue");
        Entity defender = unit(state, "thorn_guard", 100, 0, "red");
        defender.add(new ReflectDamageComponent(3, 0.5));
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();
        battle.triggerSystem().register(TriggerTiming.AFTER_DAMAGE, new ReflectDamageTrigger());

        battle.submit(new AttackCommand(attacker.id(), defender.id()));
        battle.runUntilIdle();

        assertEquals(80, defender.require(HealthComponent.class).current());
        assertEquals(87, attacker.require(HealthComponent.class).current());
    }

    @Test
    void counterAttackRespondsToNormalAttackOnlyOnce() {
        BattleState state = new BattleState();
        Entity attacker = unit(state, "rogue", 100, 20, "blue");
        Entity defender = unit(state, "duelist", 100, 12, "red");
        attacker.add(new CounterAttackComponent(1.0));
        defender.add(new CounterAttackComponent(1.0));
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();
        battle.triggerSystem().register(TriggerTiming.AFTER_DAMAGE, new CounterAttackTrigger());

        battle.submit(new AttackCommand(attacker.id(), defender.id()));
        battle.runUntilIdle();

        assertEquals(80, defender.require(HealthComponent.class).current());
        assertEquals(88, attacker.require(HealthComponent.class).current());
    }

    private static Entity unit(BattleState state, String type, int hp, int attack, String faction) {
        Entity unit = state.createEntity(type);
        unit.add(new HealthComponent(hp))
                .add(new AttributeComponent().set("attack", attack))
                .add(new FactionComponent(faction));
        return unit;
    }
}
