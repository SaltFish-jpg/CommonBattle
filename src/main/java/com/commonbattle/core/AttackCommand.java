package com.commonbattle.core;

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
