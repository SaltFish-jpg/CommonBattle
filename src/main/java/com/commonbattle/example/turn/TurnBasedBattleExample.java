package com.commonbattle.example.turn;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleLogEntry;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Buff;
import com.commonbattle.core.Entity;
import com.commonbattle.core.ModifyAttributeEffect;
import com.commonbattle.core.ApplyBuffCommand;
import com.commonbattle.core.StatusEffect;

/**
 * Runnable turn-based example: attack buff, stun control, turn advance, then monster attacks.
 */
public final class TurnBasedBattleExample {
    private TurnBasedBattleExample() {
    }

    public static void main(String[] args) {
        BattleState state = new BattleState();
        Entity hero = TurnBasedExampleFactory.createUnit(state, "hero", 100, 20, "player");
        Entity monster = TurnBasedExampleFactory.createUnit(state, "monster", 80, 12, "enemy");
        BattleContext battle = TurnBasedExampleFactory.createBattle(state, hero.id(), monster.id());

        battle.submit(new ApplyBuffCommand(hero.id(), Buff.attribute("war_song", "attack", 8, 2)));
        battle.submit(TurnBasedExampleFactory.attack(hero.id(), monster.id()));
        new ModifyAttributeEffect(monster.id(), "enrage", "attack", 3, 1).apply(battle);
        battle.submit(new ApplyBuffCommand(monster.id(), Buff.status("shield_bash_stun", StatusEffect.STUNNED, 1)));
        battle.submit(new EndTurnCommand(hero.id()));
        battle.runUntilIdle();

        battle.log().entries().forEach(TurnBasedBattleExample::print);
    }

    private static void print(BattleLogEntry entry) {
        System.out.printf("%02d %-22s %s%n", entry.sequence(), entry.type(), entry.message());
    }
}
