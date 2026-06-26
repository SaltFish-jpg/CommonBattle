package com.commonbattle.core;

import java.util.List;

/**
 * Generic skill command.
 * The skill is represented as an ordered effect list, so concrete games can build skills from data or scripts.
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
