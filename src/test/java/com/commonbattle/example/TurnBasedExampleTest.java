package com.commonbattle.example;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Entity;
import com.commonbattle.core.HealthComponent;
import com.commonbattle.example.turn.EndTurnCommand;
import com.commonbattle.example.turn.TurnBasedExampleFactory;
import com.commonbattle.example.turn.TurnComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurnBasedExampleTest {
    @Test
    void turnBasedRuleAllowsOnlyCurrentActorThenAdvancesTurn() {
        BattleState state = new BattleState();
        Entity hero = TurnBasedExampleFactory.createUnit(state, "hero", 100, 20, "player");
        Entity monster = TurnBasedExampleFactory.createUnit(state, "monster", 80, 12, "enemy");

        BattleContext battle = TurnBasedExampleFactory.createBattle(state, hero.id(), monster.id());

        battle.submit(TurnBasedExampleFactory.attack(hero.id(), monster.id()));
        battle.submit(new EndTurnCommand(hero.id()));
        battle.submit(TurnBasedExampleFactory.attack(monster.id(), hero.id()));
        battle.runUntilIdle();

        assertEquals(monster.id(), battle.state().requireGlobal(TurnComponent.class).currentActor());
        assertEquals(60, monster.require(HealthComponent.class).current());
        assertEquals(88, hero.require(HealthComponent.class).current());
    }
}
