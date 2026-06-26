package com.commonbattle.example.card;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;
import com.commonbattle.core.DealDamageEffect;
import com.commonbattle.core.Effect;
import com.commonbattle.core.Entity;
import com.commonbattle.core.EntityId;

import java.util.List;

/**
 * Card play intent.
 * The command composes spending mana, moving the card, and applying the card's damage effect.
 */
public record PlayCardCommand(EntityId player, EntityId card, EntityId target) implements Command {
    @Override
    public List<Effect> effects(BattleContext context) {
        Entity cardEntity = context.state().requireEntity(card);
        int damage = cardEntity.require(CardDamageComponent.class).amount();
        return List.of(
                new SpendManaEffect(player, card),
                new MoveCardEffect(card, Zone.GRAVEYARD),
                new DealDamageEffect(card, target, damage)
        );
    }
}
