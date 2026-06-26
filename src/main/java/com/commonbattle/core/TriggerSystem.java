package com.commonbattle.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry and dispatcher for triggers.
 * Trigger order is the registration order for each timing.
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
