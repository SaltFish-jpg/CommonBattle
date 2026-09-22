package com.commonbattle.game;

import java.util.Objects;

/**
 * 保留参数异常语义的领域业务失败。
 * 适用于配置缺失、命令参数非法、玩法类型不匹配等可预期失败。
 */
public class GameBusinessIllegalArgumentException extends IllegalArgumentException implements GameBusinessFailure {
    private final String code;
    private final long retryAfterMillis;

    public GameBusinessIllegalArgumentException(String code, String message) {
        this(code, message, 0);
    }

    public GameBusinessIllegalArgumentException(String code, String message, long retryAfterMillis) {
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
