package com.commonbattle.battle.effect;



import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.context.BattleContext;
/**
 * 原子结算操作。
 * 技能、卡牌、Buff 和规则通过组合效果实现，避免把大型玩法流程硬编码在一起。
 */
public interface Effect {
    void apply(BattleContext context);
}
