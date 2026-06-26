package com.commonbattle.example.card;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.BattleLogEntry;
import com.commonbattle.core.BattleState;
import com.commonbattle.core.Buff;
import com.commonbattle.core.Entity;
import com.commonbattle.core.ApplyBuffCommand;
import com.commonbattle.core.StatusEffect;

/**
 * Runnable card example: player casts a freeze fireball card onto a target.
 */
public final class CardBattleExample {
    private CardBattleExample() {
    }

    public static void main(String[] args) {
        BattleState state = new BattleState();
        Entity player = CardExampleFactory.createPlayer(state, 5);
        Entity fireball = CardExampleFactory.createDamageCard(state, "fireball", 3, 7);
        Entity target = CardExampleFactory.createTarget(state, 30);
        BattleContext battle = CardExampleFactory.createBattle(state);

        battle.submit(new PlayCardCommand(player.id(), fireball.id(), target.id()));
        battle.submit(new ApplyBuffCommand(target.id(), Buff.status("frozen_by_card", StatusEffect.FROZEN, 1)));
        battle.runUntilIdle();

        battle.log().entries().forEach(CardBattleExample::print);
    }

    private static void print(BattleLogEntry entry) {
        System.out.printf("%02d %-22s %s%n", entry.sequence(), entry.type(), entry.message());
    }
}
