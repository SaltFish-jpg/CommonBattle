package com.commonbattle.battle.trigger;



import com.commonbattle.battle.component.SkillComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.skill.Skill;
import com.commonbattle.battle.state.Entity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 触发器注册表和分发器。
 * 同一时机下，先执行全局注册触发器，再按实体顺序执行 SkillComponent 中命中的被动技能。
 */
public final class TriggerSystem {
    private final Map<TriggerTiming, List<Trigger>> triggers = new EnumMap<>(TriggerTiming.class);
    private final Set<PassiveSkillKey> firingPassiveSkills = new HashSet<>();

    public void register(TriggerTiming timing, Trigger trigger) {
        triggers.computeIfAbsent(timing, ignored -> new ArrayList<>()).add(trigger);
    }

    public void fire(TriggerTiming timing, BattleContext context) {
        for (Trigger trigger : List.copyOf(triggers.getOrDefault(timing, List.of()))) {
            trigger.execute(context);
        }
        firePassiveSkills(timing, context);
    }

    private void firePassiveSkills(TriggerTiming timing, BattleContext context) {
        for (Entity owner : List.copyOf(context.state().entities())) {
            owner.find(SkillComponent.class)
                    .ifPresent(skills -> firePassiveSkills(timing, context, owner, skills));
        }
    }

    private void firePassiveSkills(
            TriggerTiming timing,
            BattleContext context,
            Entity owner,
            SkillComponent skills
    ) {
        for (Skill skill : skills.passiveSkillsAt(timing)) {
            PassiveSkillKey key = new PassiveSkillKey(owner.id().value(), skill.instanceId(), timing);
            if (!skill.enabled() || !firingPassiveSkills.add(key)) {
                continue;
            }
            try {
                // 被动技能效果可能再次打开相同触发窗口，这里用 key 防止同一被动递归触发自己。
                context.log().add("skill.passive_trigger",
                        "%s trigger passive %d at %s".formatted(owner.id().value(), skill.instanceId(), timing));
                context.resolveEffects(skill.definition().effects());
            } finally {
                firingPassiveSkills.remove(key);
            }
        }
    }

    private record PassiveSkillKey(String ownerId, long skillInstanceId, TriggerTiming timing) {
    }
}
