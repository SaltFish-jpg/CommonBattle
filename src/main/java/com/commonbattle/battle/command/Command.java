package com.commonbattle.battle.command;



import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.rule.RuleSet;
import java.util.List;

/**
 * 外部输入或 AI 决策。
 * 命令描述战斗意图，并在当前 {@link RuleSet} 校验通过后展开为效果。
 */
public interface Command {
    List<Effect> effects(BattleContext context);
}
