package com.commonbattle.battle.command;



import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.AddBuffEffect;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.state.EntityId;
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
