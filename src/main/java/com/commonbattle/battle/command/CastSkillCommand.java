package com.commonbattle.battle.command;



import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.event.EventBus;
import com.commonbattle.battle.event.SkillCastEvent;
import com.commonbattle.battle.state.EntityId;
import java.util.List;

/**
 * 通用技能命令。
 * 技能表示为有序效果列表，具体游戏可以从数据或脚本构建技能。
 */
public record CastSkillCommand(EntityId caster, String skillId, List<Effect> effects) implements Command {
    public CastSkillCommand {
        effects = List.copyOf(effects);
    }

    @Override
    public List<Effect> effects(BattleContext context) {
        context.log().add("skill.cast", "%s cast %s".formatted(caster.value(), skillId));
        context.eventBus().publish(new SkillCastEvent(caster, skillId));
        return effects;
    }
}
