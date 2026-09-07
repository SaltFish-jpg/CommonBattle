package com.commonbattle.example.cross;

import java.time.Duration;
import java.util.Objects;

/**
 * 玩家进入跨服 Scene 失败后的业务快照。
 * 它把底层 RPC 异常收敛成稳定的业务语义，避免客户端或玩法层依赖异常文本。
 */
public record EnterSceneFailure(
        EnterSceneFailureCode code,
        String message,
        boolean retryable,
        Duration retryAfter
) {
    public EnterSceneFailure {
        Objects.requireNonNull(code, "code");
        message = message == null ? "" : message;
        retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }
}
