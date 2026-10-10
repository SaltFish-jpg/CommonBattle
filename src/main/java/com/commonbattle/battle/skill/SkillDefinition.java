package com.commonbattle.battle.skill;

import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.trigger.TriggerTiming;
import java.util.List;
import java.util.Objects;

/**
 * 技能静态定义。
 * 通常由配置表或脚本构建，只保存不随单场战斗变化的效果、冷却和触发点。
 */
public record SkillDefinition(
        long configId,
        String name,
        SkillType type,
        List<Effect> effects,
        int baseCooldown,
        List<TriggerTiming> triggerTimings
) {
    public SkillDefinition {
        if (configId <= 0) {
            throw new IllegalArgumentException("skill config id must be positive");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        effects = List.copyOf(effects);
        if (baseCooldown < 0) {
            throw new IllegalArgumentException("base cooldown cannot be negative");
        }
        triggerTimings = List.copyOf(triggerTimings);
    }

    public static SkillDefinition active(long configId, String name, int baseCooldown, List<Effect> effects) {
        return new SkillDefinition(
                configId,
                name,
                SkillType.ACTIVE,
                effects,
                baseCooldown,
                List.of()
        );
    }
}
