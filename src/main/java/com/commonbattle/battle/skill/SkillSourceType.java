package com.commonbattle.battle.skill;

/**
 * 技能来源类型。
 * 用于支持装备卸下、Buff 消失、天赋重置等按来源批量移除技能的场景。
 */
public enum SkillSourceType {
    INNATE,
    EQUIPMENT,
    BUFF,
    TALENT,
    CARD,
    SUMMON
}
