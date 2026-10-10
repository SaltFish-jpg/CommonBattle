package com.commonbattle.battle.component;

import com.commonbattle.battle.skill.Skill;
import com.commonbattle.battle.skill.SkillSource;
import com.commonbattle.battle.skill.SkillSourceType;
import com.commonbattle.battle.skill.SkillType;
import com.commonbattle.battle.state.Component;
import com.commonbattle.battle.trigger.TriggerTiming;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 实体当前拥有的技能仓库。
 * 技能只在主表保存一次，按类型、来源和触发点维护索引，避免多集合重复持有运行时对象。
 */
public final class SkillComponent implements Component {
    private final Map<Long, Skill> skillsByInstanceId = new LinkedHashMap<>();
    private final Map<Long, List<Long>> skillInstancesByConfigId = new HashMap<>();
    private final Map<SkillType, List<Long>> skillInstancesByType = new EnumMap<>(SkillType.class);
    private final Map<SkillSourceType, List<Long>> skillInstancesBySourceType = new EnumMap<>(SkillSourceType.class);
    private final Map<SkillSource, List<Long>> skillInstancesBySource = new HashMap<>();
    private final Map<TriggerTiming, List<Long>> passiveIndex = new EnumMap<>(TriggerTiming.class);
    private final List<Long> auraSkillInstances = new ArrayList<>();

    public SkillComponent add(Skill skill) {
        Objects.requireNonNull(skill, "skill");
        if (skillsByInstanceId.containsKey(skill.instanceId())) {
            remove(skill.instanceId());
        }
        skillsByInstanceId.put(skill.instanceId(), skill);
        index(skill);
        return this;
    }

    public Optional<Skill> find(long skillInstanceId) {
        return Optional.ofNullable(skillsByInstanceId.get(skillInstanceId));
    }

    public Skill require(long skillInstanceId) {
        return find(skillInstanceId)
                .orElseThrow(() -> new IllegalStateException("Missing skill instance " + skillInstanceId));
    }

    public List<Skill> skillsByConfigId(long skillConfigId) {
        return resolve(skillInstancesByConfigId.getOrDefault(skillConfigId, List.of()));
    }

    public Collection<Skill> all() {
        return Collections.unmodifiableCollection(skillsByInstanceId.values());
    }

    public List<Skill> skillsByType(SkillType type) {
        return resolve(skillInstancesByType.getOrDefault(type, List.of()));
    }

    public List<Skill> skillsBySourceType(SkillSourceType type) {
        return resolve(skillInstancesBySourceType.getOrDefault(type, List.of()));
    }

    public List<Skill> skillsBySource(SkillSource source) {
        return resolve(skillInstancesBySource.getOrDefault(source, List.of()));
    }

    public List<Skill> passiveSkillsAt(TriggerTiming timing) {
        return resolve(passiveIndex.getOrDefault(timing, List.of()));
    }

    public List<Skill> auraSkills() {
        return resolve(auraSkillInstances);
    }

    public boolean remove(long skillInstanceId) {
        Skill removed = skillsByInstanceId.remove(skillInstanceId);
        if (removed == null) {
            return false;
        }
        rebuildIndexes();
        return true;
    }

    public int removeBySource(SkillSource source) {
        List<Long> matched = skillsByInstanceId.values().stream()
                .filter(skill -> skill.source().equals(source))
                .map(Skill::instanceId)
                .toList();
        matched.forEach(skillsByInstanceId::remove);
        if (!matched.isEmpty()) {
            rebuildIndexes();
        }
        return matched.size();
    }

    private void index(Skill skill) {
        skillInstancesByConfigId.computeIfAbsent(skill.configId(), ignored -> new ArrayList<>()).add(skill.instanceId());
        skillInstancesByType.computeIfAbsent(skill.type(), ignored -> new ArrayList<>()).add(skill.instanceId());
        skillInstancesBySourceType.computeIfAbsent(skill.source().type(), ignored -> new ArrayList<>()).add(skill.instanceId());
        skillInstancesBySource.computeIfAbsent(skill.source(), ignored -> new ArrayList<>()).add(skill.instanceId());
        if (skill.type() == SkillType.PASSIVE) {
            skill.definition().triggerTimings()
                    .forEach(timing -> passiveIndex.computeIfAbsent(timing, ignored -> new ArrayList<>()).add(skill.instanceId()));
        }
        if (skill.type() == SkillType.AURA) {
            auraSkillInstances.add(skill.instanceId());
        }
    }

    private void rebuildIndexes() {
        skillInstancesByConfigId.clear();
        skillInstancesByType.clear();
        skillInstancesBySourceType.clear();
        skillInstancesBySource.clear();
        passiveIndex.clear();
        auraSkillInstances.clear();
        skillsByInstanceId.values().forEach(this::index);
    }

    private List<Skill> resolve(List<Long> skillInstanceIds) {
        return skillInstanceIds.stream()
                .map(skillsByInstanceId::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
