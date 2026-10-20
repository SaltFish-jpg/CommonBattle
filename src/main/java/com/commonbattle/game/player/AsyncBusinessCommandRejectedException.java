package com.commonbattle.game.player;

import com.commonbattle.game.GameBusinessFailure;

/**
 * 玩家异步业务命令被本地治理策略拒绝。
 */
public final class AsyncBusinessCommandRejectedException extends RuntimeException implements GameBusinessFailure {
    public static final String CODE = "ASYNC_COMMAND_PENDING_LIMIT";

    private final String operation;
    private final int maxPending;

    public AsyncBusinessCommandRejectedException(String operation, int maxPending) {
        super("async command pending limit reached for " + operation + ": " + maxPending);
        this.operation = operation;
        this.maxPending = maxPending;
    }

    public String operation() {
        return operation;
    }

    public int maxPending() {
        return maxPending;
    }

    @Override
    public String code() {
        return CODE;
    }
}
