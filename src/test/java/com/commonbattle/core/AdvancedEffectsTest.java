package com.commonbattle.core;

import org.junit.jupiter.api.Test;

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

    private static Entity unit(BattleState state, String type, int hp, int attack, String faction) {
        Entity unit = state.createEntity(type);
        unit.add(new HealthComponent(hp))
                .add(new AttributeComponent().set("attack", attack))
                .add(new FactionComponent(faction));
        return unit;
    }
}
