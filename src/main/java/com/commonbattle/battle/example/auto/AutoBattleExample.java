package com.commonbattle.battle.example.auto;




import com.commonbattle.battle.component.RageEnergyComponent;
import com.commonbattle.battle.component.RageSkillComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.AreaDamageEffect;
import com.commonbattle.battle.log.BattleLogEntry;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.targeting.TargetSelector;
import java.util.List;

/**
 * 可运行的自动战斗示例：攻击回怒，满怒后自动释放旋风斩。
 */
public final class AutoBattleExample {
    private AutoBattleExample() {
    }

    public static void main(String[] args) {
        BattleState state = new BattleState();
        Entity hero = AutoBattleExampleFactory.createFighter(state, "hero", 100, 15, "player");
        AutoBattleExampleFactory.createFighter(state, "monster", 40, 9, "enemy");
        AutoBattleExampleFactory.createFighter(state, "shaman", 35, 7, "enemy");
        hero.add(new RageEnergyComponent(100, 80, 10))
                .add(new RageSkillComponent(
                        "whirlwind",
                        List.of(new AreaDamageEffect(hero.id(), TargetSelector.enemiesOf("player"), 6))
                ));
        BattleContext battle = AutoBattleExampleFactory.createBattle(state);

        battle.submit(new TickCommand());
        battle.runUntilIdle();

        battle.log().entries().forEach(AutoBattleExample::print);
    }

    private static void print(BattleLogEntry entry) {
        System.out.printf("%02d %-22s %s%n", entry.sequence(), entry.type(), entry.message());
    }
}
