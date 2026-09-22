package com.commonbattle.example;




import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.example.auto.AutoBattleExampleFactory;
import com.commonbattle.battle.example.auto.TickCommand;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoBattleExampleTest {
    @Test
    void autoBattleTickChoosesAliveEnemyAndRunsAttackCommand() {
        BattleState state = new BattleState();
        Entity hero = AutoBattleExampleFactory.createFighter(state, "hero", 100, 15, "player");
        Entity monster = AutoBattleExampleFactory.createFighter(state, "monster", 40, 9, "enemy");

        BattleContext battle = AutoBattleExampleFactory.createBattle(state);
        battle.submit(new TickCommand());
        battle.runUntilIdle();

        assertEquals(25, monster.require(HealthComponent.class).current());
        assertEquals(100, hero.require(HealthComponent.class).current());
    }
}
