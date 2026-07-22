package com.commonbattle.example.card;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;
import com.commonbattle.core.DealDamageEffect;
import com.commonbattle.core.Effect;
import com.commonbattle.core.Entity;
import com.commonbattle.core.EntityId;

import java.util.List;

/**
 * 出牌意图。
 * 该命令组合了消耗法力、移动卡牌和结算卡牌伤害效果。
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
