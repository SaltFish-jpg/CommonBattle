package com.commonbattle.battle.skill;

/**
 * 技能来源。
 * type + sourceId 组成复合来源键，避免业务把装备、Buff、天赋等来源拼成字符串。
 */
public record SkillSource(SkillSourceType type, long sourceId) {
    public SkillSource {
        if (type == null) {
            throw new IllegalArgumentException("skill source type cannot be null");
        }
    }

    public static SkillSource innate(long sourceId) {
        return new SkillSource(SkillSourceType.INNATE, sourceId);
    }

    public static SkillSource equipment(long sourceId) {
        return new SkillSource(SkillSourceType.EQUIPMENT, sourceId);
    }

    public static SkillSource buff(long sourceId) {
        return new SkillSource(SkillSourceType.BUFF, sourceId);
    }
}
