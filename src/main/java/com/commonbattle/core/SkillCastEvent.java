package com.commonbattle.core;

/**
 * Published when a skill command expands into its effects.
 */
public record SkillCastEvent(EntityId caster, String skillId) implements Event {
}
