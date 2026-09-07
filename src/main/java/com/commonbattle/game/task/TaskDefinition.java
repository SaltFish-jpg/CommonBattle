package com.commonbattle.game.task;

import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.player.event.EventProgressRule;

import java.util.Objects;

/**
 * 玩家任务配置。
 * eventType/subject 描述任务监听的玩家业务事件，subject 为空表示监听该类型下所有事件。
 */
public record TaskDefinition(
        String taskId,
        EventProgressRule progressRule,
        int threshold,
        Reward reward
) {
    public TaskDefinition(String taskId, String eventType, String subject, int threshold, Reward reward) {
        this(taskId, EventProgressRule.of(eventType, subject), threshold, reward);
    }

    public TaskDefinition {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(progressRule, "progressRule");
        Objects.requireNonNull(reward, "reward");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (!progressRule.enabled()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
    }

    public boolean matches(String actualType, String actualSubject) {
        return progressRule.matches(new SimplePlayerDomainEvent(actualType, actualSubject, 1));
    }

    public String eventType() {
        return progressRule.eventType();
    }

    public String subject() {
        return progressRule.subject();
    }

    private record SimplePlayerDomainEvent(String type, String subject, int delta) implements com.commonbattle.game.player.event.PlayerDomainEvent {
    }
}
