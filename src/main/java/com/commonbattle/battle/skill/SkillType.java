package com.commonbattle.battle.skill;

/**
 * 技能类型。
 * 主动技能由命令释放，被动技能由触发点驱动，光环技能持续维护范围影响。
 */
public enum SkillType {
    ACTIVE,
    PASSIVE,
    AURA
}
