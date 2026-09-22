package com.commonbattle.battle.component;



import com.commonbattle.battle.state.Component;
/**
 * 实体被普通攻击命中后反击的配置。
 * 反击伤害基于自身 attack 属性乘以倍率计算。
 */
public record CounterAttackComponent(double damageRatio) implements Component {
    public CounterAttackComponent {
        if (damageRatio < 0) {
            throw new IllegalArgumentException("counter damage ratio must be non-negative");
        }
    }
}
