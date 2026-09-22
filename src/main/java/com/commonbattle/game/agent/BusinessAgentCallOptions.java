package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorTaskCategory;

import java.time.Duration;
import java.util.Objects;

/**
 * 业务 Agent 请求选项。
 * requestCategory 控制目标 Actor 邮箱中的任务类别，callbackCategory 控制远程回包回到请求方 Actor 邮箱时的类别。
 */
public record BusinessAgentCallOptions(
        Duration timeout,
        ActorTaskCategory requestCategory,
        ActorTaskCategory callbackCategory,
        String idempotencyKey
) {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    public BusinessAgentCallOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(requestCategory, "requestCategory");
        Objects.requireNonNull(callbackCategory, "callbackCategory");
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static BusinessAgentCallOptions defaults() {
        return of(DEFAULT_TIMEOUT);
    }

    public static BusinessAgentCallOptions of(Duration timeout) {
        return new BusinessAgentCallOptions(timeout, ActorTaskCategory.DEFAULT, ActorTaskCategory.RPC_CALLBACK, "");
    }

    public BusinessAgentCallOptions withRequestCategory(ActorTaskCategory category) {
        return new BusinessAgentCallOptions(timeout, category, callbackCategory, idempotencyKey);
    }

    public BusinessAgentCallOptions withCallbackCategory(ActorTaskCategory category) {
        return new BusinessAgentCallOptions(timeout, requestCategory, category, idempotencyKey);
    }

    public BusinessAgentCallOptions withIdempotencyKey(String key) {
        return new BusinessAgentCallOptions(timeout, requestCategory, callbackCategory, key);
    }
}
