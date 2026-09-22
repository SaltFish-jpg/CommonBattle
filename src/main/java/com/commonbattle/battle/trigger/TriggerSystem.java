package com.commonbattle.battle.trigger;



import com.commonbattle.battle.context.BattleContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 触发器注册表和分发器。
 * 同一时机下，触发器按注册顺序执行。
 */
public final class TriggerSystem {
    private final Map<TriggerTiming, List<Trigger>> triggers = new EnumMap<>(TriggerTiming.class);

    public void register(TriggerTiming timing, Trigger trigger) {
        triggers.computeIfAbsent(timing, ignored -> new ArrayList<>()).add(trigger);
    }

    public void fire(TriggerTiming timing, BattleContext context) {
        for (Trigger trigger : List.copyOf(triggers.getOrDefault(timing, List.of()))) {
            trigger.execute(context);
        }
    }
}
