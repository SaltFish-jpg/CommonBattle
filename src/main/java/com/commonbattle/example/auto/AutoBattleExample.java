package com.commonbattle.example.auto;

import com.commonbattle.core.AreaDamageEffect;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleLogEntry;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.CastSkillCommand;
import com.commonbattle.core.Entity;
import com.commonbattle.core.TargetSelector;

import java.util.List;

/**
 * Runnable auto-battle example: one tick queues an attack, then a skill performs enemy-side area damage.
 */
public final class AutoBattleExample {
    private AutoBattleExample() {
    }

    public static void main(String[] args) {
        BattleState state = new BattleState();
        AutoBattleExampleFactory.createFighter(state, "hero", 100, 15, "player");
        AutoBattleExampleFactory.createFighter(state, "monster", 40, 9, "enemy");
        AutoBattleExampleFactory.createFighter(state, "shaman", 35, 7, "enemy");
        BattleContext battle = AutoBattleExampleFactory.createBattle(state);

        battle.submit(new TickCommand());
        Entity caster = AutoBattleRuleSet.firstAliveInFaction(battle, "player");
        battle.submit(new CastSkillCommand(
                caster.id(),
                "whirlwind",
                List.of(new AreaDamageEffect(caster.id(), TargetSelector.enemiesOf("player"), 6))
        ));
        battle.runUntilIdle();

        battle.log().entries().forEach(AutoBattleExample::print);
    }

    private static void print(BattleLogEntry entry) {
        System.out.printf("%02d %-22s %s%n", entry.sequence(), entry.type(), entry.message());
    }
}
