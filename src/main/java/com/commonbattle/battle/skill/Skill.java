package com.commonbattle.battle.skill;

import java.util.Objects;

/**
 * 实体拥有的技能运行时实例。
 * 冷却、充能、等级和禁用状态属于具体实体，不放进静态技能定义。
 */
public final class Skill {
    private final long instanceId;
    private final SkillDefinition definition;
    private final SkillSource source;
    private boolean enabled = true;
    private int cooldownRemaining;
    private int charges;
    private int level = 1;

    public Skill(long instanceId, SkillDefinition definition, SkillSource source) {
        if (instanceId <= 0) {
            throw new IllegalArgumentException("skill instance id must be positive");
        }
        this.instanceId = instanceId;
        this.definition = Objects.requireNonNull(definition, "definition");
        this.source = Objects.requireNonNull(source, "source");
    }

    public long instanceId() {
        return instanceId;
    }

    public SkillDefinition definition() {
        return definition;
    }

    public SkillSource source() {
        return source;
    }

    public long configId() {
        return definition.configId();
    }

    public SkillType type() {
        return definition.type();
    }

    public boolean enabled() {
        return enabled;
    }

    public Skill setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public int cooldownRemaining() {
        return cooldownRemaining;
    }

    public Skill setCooldownRemaining(int cooldownRemaining) {
        if (cooldownRemaining < 0) {
            throw new IllegalArgumentException("cooldown remaining cannot be negative");
        }
        this.cooldownRemaining = cooldownRemaining;
        return this;
    }

    public int charges() {
        return charges;
    }

    public Skill setCharges(int charges) {
        if (charges < 0) {
            throw new IllegalArgumentException("charges cannot be negative");
        }
        this.charges = charges;
        return this;
    }

    public int level() {
        return level;
    }

    public Skill setLevel(int level) {
        if (level <= 0) {
            throw new IllegalArgumentException("level must be positive");
        }
        this.level = level;
        return this;
    }

    public void startCooldown() {
        this.cooldownRemaining = definition.baseCooldown();
    }

    public void reduceCooldown(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("cooldown reduce amount cannot be negative");
        }
        cooldownRemaining = Math.max(0, cooldownRemaining - amount);
    }
}
