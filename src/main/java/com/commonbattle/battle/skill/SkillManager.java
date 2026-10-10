package com.commonbattle.battle.skill;

import com.commonbattle.battle.component.SkillComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.event.SkillCastEvent;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.state.EntityId;
import java.util.List;

/**
 * 技能结算门面。
 * 负责从实体技能仓库取运行时技能、校验可释放状态，并展开静态定义中的效果。
 */
public final class SkillManager {
    public Skill requireActiveSkill(BattleContext context, EntityId casterId, long skillConfigId) {
        Entity caster = context.state().requireEntity(casterId);
        Skill skill = resolveActiveSkill(caster.require(SkillComponent.class), skillConfigId);
        if (skill.type() != SkillType.ACTIVE) {
            throw new IllegalStateException("Only active skill can be cast by command: " + skillConfigId);
        }
        if (!skill.enabled()) {
            throw new IllegalStateException("Skill is disabled: " + skillConfigId);
        }
        if (skill.cooldownRemaining() > 0) {
            throw new IllegalStateException("Skill is cooling down: " + skillConfigId);
        }
        return skill;
    }

    public List<Effect> castActiveSkill(BattleContext context, EntityId casterId, long skillConfigId) {
        Skill skill = requireActiveSkill(context, casterId, skillConfigId);
        skill.startCooldown();
        context.log().add("skill.cast", "%s cast %d".formatted(casterId.value(), skillConfigId));
        context.eventBus().publish(new SkillCastEvent(casterId, Long.toString(skillConfigId)));
        return skill.definition().effects();
    }

    private Skill resolveActiveSkill(SkillComponent skills, long skillConfigId) {
        List<Skill> candidates = skills.skillsByConfigId(skillConfigId).stream()
                .filter(skill -> skill.type() == SkillType.ACTIVE)
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Missing active skill config " + skillConfigId);
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Multiple active skill instances for config " + skillConfigId);
        }
        return candidates.get(0);
    }
}
