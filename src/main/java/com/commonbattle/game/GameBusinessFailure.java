package com.commonbattle.game;

/**
 * 可跨 Actor、RPC 和网关信封传递的稳定业务失败。
 * 领域服务抛出该类型后，玩家业务回包会优先使用这里的错误码，而不是退化成通用异常分类。
 */
public interface GameBusinessFailure {
    String code();

    default long retryAfterMillis() {
        return 0;
    }
}
