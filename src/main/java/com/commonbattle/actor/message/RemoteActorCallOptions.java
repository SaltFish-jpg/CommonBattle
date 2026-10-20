package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorTaskCategory;

import java.time.Duration;
import java.util.Objects;

/**
 * 远程 Actor 调用选项。
 * timeout 控制调用侧等待上限，callbackCategory 控制成功、失败或超时回投请求方邮箱时的任务类别。
 */
public record RemoteActorCallOptions(Duration timeout, ActorTaskCategory callbackCategory) {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    public RemoteActorCallOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(callbackCategory, "callbackCategory");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static RemoteActorCallOptions defaults() {
        return of(DEFAULT_TIMEOUT);
    }

    public static RemoteActorCallOptions of(Duration timeout) {
        return new RemoteActorCallOptions(timeout, ActorTaskCategory.RPC_CALLBACK);
    }

    public RemoteActorCallOptions withCallbackCategory(ActorTaskCategory category) {
        return new RemoteActorCallOptions(timeout, category);
    }
}
