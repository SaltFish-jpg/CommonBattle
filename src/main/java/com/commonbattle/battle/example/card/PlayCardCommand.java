package com.commonbattle.battle.example.card;




import com.commonbattle.battle.command.Command;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.DealDamageEffect;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.state.EntityId;
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
