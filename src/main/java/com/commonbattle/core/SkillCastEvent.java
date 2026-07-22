package com.commonbattle.core;

/**
 * 技能命令展开为效果时发布的事件。
 */
public record SkillCastEvent(EntityId caster, String skillId) implements Event {
}
