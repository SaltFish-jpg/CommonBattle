package com.commonbattle.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 单场战斗的运行时容器。
 * 负责把状态、规则、命令队列、触发器、效果、随机数、事件和日志串成统一结算流水线。
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
     * 清空待处理命令。效果可以继续提交新命令，新命令会在同一次运行中继续结算。
     */
    public void runUntilIdle() {
        while (!commandQueue.isEmpty()) {
            commandQueue.poll().ifPresent(this::execute);
        }
    }

    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(type.cast(scoped.get(type)));
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
        withScoped(Command.class, command, () -> {
            ruleSet.validate(this, command);
            triggerSystem.fire(TriggerTiming.BEFORE_COMMAND, this);
            effectResolver.resolve(this, command.effects(this));
            triggerSystem.fire(TriggerTiming.AFTER_COMMAND, this);
            log.add("command.completed", command.getClass().getSimpleName() + " completed");
        });
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
