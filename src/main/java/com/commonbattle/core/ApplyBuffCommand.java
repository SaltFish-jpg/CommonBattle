package com.commonbattle.core;

import java.util.List;

/**
 * Command form for adding a buff from an external input, AI decision, card, or skill.
 */
public record ApplyBuffCommand(EntityId target, Buff buff) implements Command {
    @Override
    public List<Effect> effects(BattleContext context) {
        return List.of(new AddBuffEffect(target, buff));
    }
}
