package com.commonbattle.battle.component;



import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.buff.StatusEffect;
import com.commonbattle.battle.state.Component;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存单个实体身上的当前 Buff。
 * 规则集和属性解析器会读取该组件，以应用属性修正和控制状态。
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
