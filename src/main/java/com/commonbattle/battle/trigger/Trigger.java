package com.commonbattle.battle.trigger;



import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.context.BattleContext;
/**
 * 伤害前、回合开始等有序结算时机的挂钩。
 * Buff、被动、装备和光环逻辑通常通过触发器实现。
 */
@FunctionalInterface
public interface Trigger {
    void execute(BattleContext context);
}
