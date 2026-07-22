package com.commonbattle.example.auto;

import com.commonbattle.core.AreaDamageEffect;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleLogEntry;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Entity;
import com.commonbattle.core.RageEnergyComponent;
import com.commonbattle.core.RageSkillComponent;
import com.commonbattle.core.TargetSelector;

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
