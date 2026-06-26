package com.commonbattle.example;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Entity;
import com.commonbattle.core.HealthComponent;
import com.commonbattle.example.card.CardExampleFactory;
import com.commonbattle.example.card.ManaComponent;
import com.commonbattle.example.card.PlayCardCommand;
import com.commonbattle.example.card.Zone;
import com.commonbattle.example.card.ZoneComponent;
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
