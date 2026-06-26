package com.commonbattle.example.card;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;
import com.commonbattle.core.Entity;
import com.commonbattle.core.RuleSet;

/**
 * Example rule set for card games.
 * It checks hand zone, enough mana, and target existence before a card can be played.
 */
public final class CardRuleSet implements RuleSet {
    @Override
    public void validate(BattleContext context, Command command) {
        if (command instanceof PlayCardCommand play) {
            Entity player = context.state().requireEntity(play.player());
            Entity card = context.state().requireEntity(play.card());
            context.state().requireEntity(play.target());
            if (card.require(ZoneComponent.class).zone() != Zone.HAND) {
                throw new IllegalStateException("card must be in hand");
            }
            int currentMana = player.require(ManaComponent.class).current();
            int cost = card.require(CostComponent.class).mana();
            if (currentMana < cost) {
                throw new IllegalStateException("not enough mana");
            }
        }
    }
}
