package com.commonbattle.core;

import java.util.List;

/**
 * 从外部输入、AI 决策、卡牌或技能添加 Buff 的命令形式。
 */
public record ApplyBuffCommand(EntityId target, Buff buff) implements Command {
    @Override
    public List<Effect> effects(BattleContext context) {
        return List.of(new AddBuffEffect(target, buff));
    }
}
