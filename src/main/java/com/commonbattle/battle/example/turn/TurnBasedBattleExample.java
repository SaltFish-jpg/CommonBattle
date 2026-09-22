package com.commonbattle.battle.example.turn;




import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.buff.StatusEffect;
import com.commonbattle.battle.command.ApplyBuffCommand;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.ModifyAttributeEffect;
import com.commonbattle.battle.log.BattleLogEntry;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
/**
 * 可运行的回合制示例：攻击增益、眩晕控制、推进回合，然后怪物攻击。
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
