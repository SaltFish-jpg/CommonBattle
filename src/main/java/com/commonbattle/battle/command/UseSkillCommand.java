package com.commonbattle.battle.command;

import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.skill.SkillManager;
import com.commonbattle.battle.state.EntityId;
import java.util.List;
import java.util.Objects;

/**
 * 按实体已拥有的技能释放主动技能。
 * 命令只表达释放意图，具体效果从 SkillDefinition 展开，便于配置表和运行时状态解耦。
 */
public record UseSkillCommand(EntityId caster, long skillConfigId) implements Command {
    public UseSkillCommand {
        Objects.requireNonNull(caster, "caster");
        if (skillConfigId <= 0) {
            throw new IllegalArgumentException("skill config id must be positive");
        }
    }

    @Override
    public List<Effect> effects(BattleContext context) {
        return new SkillManager().castActiveSkill(context, caster, skillConfigId);
    }
}
