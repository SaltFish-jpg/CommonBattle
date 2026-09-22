package com.commonbattle.example;




import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.example.card.CardExampleFactory;
import com.commonbattle.battle.example.card.ManaComponent;
import com.commonbattle.battle.example.card.PlayCardCommand;
import com.commonbattle.battle.example.card.Zone;
import com.commonbattle.battle.example.card.ZoneComponent;
import com.commonbattle.battle.state.BattleState;
import com.commonbattle.battle.state.Entity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardExampleTest {
    @Test
    void cardRuleConsumesManaMovesCardAndDealsConfiguredDamage() {
        BattleState state = new BattleState();
        Entity player = CardExampleFactory.createPlayer(state, 5);
        Entity fireball = CardExampleFactory.createDamageCard(state, "fireball", 3, 7);
        Entity target = CardExampleFactory.createTarget(state, 30);

        BattleContext battle = CardExampleFactory.createBattle(state);
        battle.submit(new PlayCardCommand(player.id(), fireball.id(), target.id()));
        battle.runUntilIdle();

        assertEquals(2, player.require(ManaComponent.class).current());
        assertEquals(Zone.GRAVEYARD, fireball.require(ZoneComponent.class).zone());
        assertEquals(23, target.require(HealthComponent.class).current());
    }
}
