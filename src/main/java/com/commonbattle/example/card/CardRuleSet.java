package com.commonbattle.example.card;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;
import com.commonbattle.core.Entity;
import com.commonbattle.core.RuleSet;

/**
 * 卡牌玩法示例规则集。
 * 出牌前会校验手牌区域、法力是否足够以及目标是否存在。
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
