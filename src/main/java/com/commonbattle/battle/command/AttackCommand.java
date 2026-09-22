package com.commonbattle.battle.command;



import com.commonbattle.battle.buff.AttributeValueResolver;
import com.commonbattle.battle.component.AttributeComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.DealDamageEffect;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.state.EntityId;
import java.util.List;

/**
 * 基础物理攻击命令。
 * 伤害读取攻击者 {@link AttributeComponent} 中名为 {@code attack} 的属性。
 */
public record AttackCommand(EntityId attacker, EntityId target) implements Command {
    @Override
    public List<Effect> effects(BattleContext context) {
        Entity source = context.state().requireEntity(attacker);
        int amount = AttributeValueResolver.resolve(source, "attack");
        return List.of(new DealDamageEffect(attacker, target, amount));
    }
}
