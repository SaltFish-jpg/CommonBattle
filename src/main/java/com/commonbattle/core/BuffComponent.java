package com.commonbattle.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds active buffs for one entity.
 * Rule sets and value resolvers query this component to apply modifiers and control states.
 */
public final class BuffComponent implements Component {
    private final Map<String, Buff> buffs = new LinkedHashMap<>();

    public void add(Buff buff) {
        buffs.put(buff.id(), buff);
    }

    public boolean has(String id) {
        return buffs.containsKey(id);
    }

    public boolean hasStatus(StatusEffect status) {
        return buffs.values().stream().anyMatch(buff -> buff.statusEffect().map(status::equals).orElse(false));
    }

    public int attributeBonus(String attribute) {
        return buffs.values().stream()
                .filter(buff -> buff.attributeName().map(attribute::equals).orElse(false))
                .mapToInt(Buff::attributeDelta)
                .sum();
    }

    public Collection<Buff> buffs() {
        return buffs.values();
    }
}
