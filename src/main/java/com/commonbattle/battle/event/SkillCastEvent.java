package com.commonbattle.battle.event;



import com.commonbattle.battle.state.EntityId;
/**
 * 技能命令展开为效果时发布的事件。
 */
public record SkillCastEvent(EntityId caster, String skillId) implements Event {
}
