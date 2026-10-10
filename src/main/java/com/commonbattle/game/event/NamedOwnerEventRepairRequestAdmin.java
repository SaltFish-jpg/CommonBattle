package com.commonbattle.game.event;

import java.util.Collection;
import java.util.Objects;

/**
 * 带 target/topic 标识的 owner 修复人工重放入口。
 * 启动层为每个事件 topic 包装一个实例，运维 HTTP 按 target 或 topic 路由到对应调度器。
 */
public final class NamedOwnerEventRepairRequestAdmin implements OwnerEventRepairRequestAdmin {
    private final String target;
    private final String topic;
    private final OwnerEventRepairScheduler scheduler;

    public NamedOwnerEventRepairRequestAdmin(String target, String topic, OwnerEventRepairScheduler scheduler) {
        this.target = requireText(target, "target");
        this.topic = requireText(topic, "topic");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public String target() {
        return target;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public void requestRepairOwners(Collection<String> ownerKeys) {
        scheduler.requestRepairOwners(ownerKeys);
    }

    @Override
    public void requestRepairOwners(Collection<String> ownerKeys, int priority) {
        scheduler.requestRepairOwners(ownerKeys, priority);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
