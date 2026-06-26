package com.commonbattle.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime container for one battle instance.
 * It wires state, rules, command queue, triggers, effects, random, events, and logs into one settlement pipeline.
 */
public final class BattleContext {
    private final BattleState state;
    private final RuleSet ruleSet;
    private final CommandQueue commandQueue;
    private final EventBus eventBus;
    private final TriggerSystem triggerSystem;
    private final EffectResolver effectResolver;
    private final RandomService randomService;
    private final BattleLog log;
    private final Map<Class<?>, Object> scoped = new HashMap<>();

    private BattleContext(Builder builder) {
        this.state = Objects.requireNonNullElseGet(builder.state, BattleState::new);
        this.ruleSet = Objects.requireNonNullElseGet(builder.ruleSet, BasicRuleSet::new);
        this.commandQueue = Objects.requireNonNullElseGet(builder.commandQueue, CommandQueue::new);
        this.eventBus = Objects.requireNonNullElseGet(builder.eventBus, EventBus::new);
        this.triggerSystem = Objects.requireNonNullElseGet(builder.triggerSystem, TriggerSystem::new);
        this.effectResolver = Objects.requireNonNullElseGet(builder.effectResolver, EffectResolver::new);
        this.randomService = Objects.requireNonNullElseGet(builder.randomService, () -> new RandomService(1L));
        this.log = Objects.requireNonNullElseGet(builder.log, BattleLog::new);
    }

    public static Builder builder() {
        return new Builder();
    }

    public BattleState state() {
        return state;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public TriggerSystem triggerSystem() {
        return triggerSystem;
    }

    public BattleLog log() {
        return log;
    }

    public RandomService randomService() {
        return randomService;
    }

    public void submit(Command command) {
        commandQueue.add(command);
    }

    /**
     * Drains queued commands. Effects may enqueue additional commands, which are processed in the same run.
     */
    public void runUntilIdle() {
        while (!commandQueue.isEmpty()) {
            commandQueue.poll().ifPresent(this::execute);
        }
    }

    public <T> T require(Class<T> type) {
        Object value = scoped.get(type);
        if (value == null) {
            throw new IllegalStateException("No scoped value for " + type.getSimpleName());
        }
        return type.cast(value);
    }

    <T> void withScoped(Class<T> type, T value, Runnable action) {
        Object previous = scoped.put(type, value);
        try {
            action.run();
        } finally {
            if (previous == null) {
                scoped.remove(type);
            } else {
                scoped.put(type, previous);
            }
        }
    }

    private void execute(Command command) {
        ruleSet.validate(this, command);
        triggerSystem.fire(TriggerTiming.BEFORE_COMMAND, this);
        effectResolver.resolve(this, command.effects(this));
        triggerSystem.fire(TriggerTiming.AFTER_COMMAND, this);
        log.add("command.completed", command.getClass().getSimpleName() + " completed");
    }

    public static final class Builder {
        private BattleState state;
        private RuleSet ruleSet;
        private CommandQueue commandQueue;
        private EventBus eventBus;
        private TriggerSystem triggerSystem;
        private EffectResolver effectResolver;
        private RandomService randomService;
        private BattleLog log;

        public Builder state(BattleState state) {
            this.state = state;
            return this;
        }

        public Builder ruleSet(RuleSet ruleSet) {
            this.ruleSet = ruleSet;
            return this;
        }

        public Builder commandQueue(CommandQueue commandQueue) {
            this.commandQueue = commandQueue;
            return this;
        }

        public Builder eventBus(EventBus eventBus) {
            this.eventBus = eventBus;
            return this;
        }

        public Builder triggerSystem(TriggerSystem triggerSystem) {
            this.triggerSystem = triggerSystem;
            return this;
        }

        public Builder effectResolver(EffectResolver effectResolver) {
            this.effectResolver = effectResolver;
            return this;
        }

        public Builder randomService(RandomService randomService) {
            this.randomService = randomService;
            return this;
        }

        public Builder log(BattleLog log) {
            this.log = log;
            return this;
        }

        public BattleContext build() {
            return new BattleContext(this);
        }
    }
}
