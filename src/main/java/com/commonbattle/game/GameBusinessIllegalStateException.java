package com.commonbattle.game;

import java.util.Objects;

/**
 * 保留状态异常语义的领域业务失败。
 * 适用于资源不足、领奖未达成、活动未开放等玩家状态导致的可预期失败。
 */
public class GameBusinessIllegalStateException extends IllegalStateException implements GameBusinessFailure {
    private final String code;
    private final long retryAfterMillis;

    public GameBusinessIllegalStateException(String code, String message) {
        this(code, message, 0);
    }

    public GameBusinessIllegalStateException(String code, String message, long retryAfterMillis) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
