package com.commonbattle.battle;

import com.commonbattle.battle.command.UseSkillCommand;
import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.component.SkillComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.DamageKind;
import com.commonbattle.battle.effect.DealDamageEffect;
import com.commonbattle.battle.event.Event;
import com.commonbattle.battle.event.SkillCastEvent;
import com.commonbattle.battle.rule.BasicRuleSet;
import com.commonbattle.battle.skill.Skill;
import com.commonbattle.battle.skill.SkillDefinition;
import com.commonbattle.battle.skill.SkillSource;
import com.commonbattle.battle.skill.SkillSourceType;
import com.commonbattle.battle.skill.SkillType;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.trigger.TriggerTiming;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillComponentTest {
    @Test
    void useSkillCommandCastsOwnedActiveSkillAndStartsCooldown() {
        BattleState state = new BattleState();
        Entity caster = unit(state, "mage", 100);
        Entity target = unit(state, "dummy", 60);
        Skill fireball = new Skill(
                10_001L,
                SkillDefinition.active(
                        1_001L,
                        "火球术",
                        2,
                        List.of(new DealDamageEffect(caster.id(), target.id(), 18))
                ),
                SkillSource.innate(100L)
        );
        caster.add(new SkillComponent().add(fireball));
        List<Event> events = new ArrayList<>();
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();
        battle.eventBus().subscribe(events::add);

        battle.submit(new UseSkillCommand(caster.id(), 1_001L));
        battle.runUntilIdle();

        assertEquals(42, target.require(HealthComponent.class).current());
        assertEquals(2, fireball.cooldownRemaining());
        assertTrue(events.stream().anyMatch(event -> event instanceof SkillCastEvent skill
                && skill.skillId().equals("1001")));
    }

    @Test
    void activeSkillCannotBeCastDuringCooldown() {
        BattleState state = new BattleState();
        Entity caster = unit(state, "mage", 100);
        Entity target = unit(state, "dummy", 60);
        Skill fireball = new Skill(
                10_001L,
                SkillDefinition.active(
                        1_001L,
                        "火球术",
                        1,
                        List.of(new DealDamageEffect(caster.id(), target.id(), 18))
                ),
                SkillSource.innate(100L)
        );
        caster.add(new SkillComponent().add(fireball));
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();

        battle.submit(new UseSkillCommand(caster.id(), 1_001L));
        battle.runUntilIdle();

        assertThrows(IllegalStateException.class, () -> {
            battle.submit(new UseSkillCommand(caster.id(), 1_001L));
            battle.runUntilIdle();
        });
    }

    @Test
    void skillComponentIndexesByTypeSourceAndTrigger() {
        Skill active = new Skill(
                20_001L,
                SkillDefinition.active(2_001L, "斩击", 0, List.of()),
                SkillSource.innate(200L)
        );
        SkillSource ringSource = new SkillSource(SkillSourceType.EQUIPMENT, 30_001L);
        Skill passive = new Skill(
                20_002L,
                new SkillDefinition(
                        2_002L,
                        "反击",
                        SkillType.PASSIVE,
                        List.of(),
                        0,
                        List.of(TriggerTiming.AFTER_DAMAGE)
                ),
                ringSource
        );
        Skill aura = new Skill(
                20_003L,
                new SkillDefinition(
                        2_003L,
                        "勇气光环",
                        SkillType.AURA,
                        List.of(),
                        0,
                        List.of()
                ),
                ringSource
        );
        SkillComponent skills = new SkillComponent()
                .add(active)
                .add(passive)
                .add(aura);

        assertEquals(List.of(active), skills.skillsByType(SkillType.ACTIVE));
        assertEquals(List.of(active), skills.skillsByConfigId(2_001L));
        assertEquals(List.of(passive), skills.passiveSkillsAt(TriggerTiming.AFTER_DAMAGE));
        assertEquals(List.of(aura), skills.auraSkills());
        assertEquals(2, skills.skillsBySource(ringSource).size());

        assertEquals(2, skills.removeBySource(ringSource));
        assertEquals(List.of(), skills.skillsBySource(ringSource));
        assertEquals(List.of(), skills.auraSkills());
    }

    @Test
    void triggerSystemFiresPassiveSkillsFromSkillComponent() {
        BattleState state = new BattleState();
        Entity attacker = unit(state, "rogue", 100);
        Entity defender = unit(state, "thorn_guard", 80);
        defender.add(new SkillComponent().add(new Skill(
                30_001L,
                new SkillDefinition(
                        3_001L,
                        "荆棘皮肤",
                        SkillType.PASSIVE,
                        List.of(new DealDamageEffect(defender.id(), attacker.id(), 7, DamageKind.REFLECT)),
                        0,
                        List.of(TriggerTiming.AFTER_DAMAGE)
                ),
                SkillSource.innate(300L)
        )));
        BattleContext battle = BattleContext.builder().state(state).ruleSet(new BasicRuleSet()).build();

        battle.resolveEffects(List.of(new DealDamageEffect(attacker.id(), defender.id(), 10)));

        assertEquals(70, defender.require(HealthComponent.class).current());
        assertEquals(93, attacker.require(HealthComponent.class).current());
        assertTrue(battle.log().entries().stream()
                .anyMatch(entry -> entry.type().equals("skill.passive_trigger")));
    }

    private static Entity unit(BattleState state, String type, int hp) {
        Entity unit = state.createEntity(type);
        unit.add(new HealthComponent(hp));
        return unit;
    }
}
