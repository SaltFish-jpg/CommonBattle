package com.commonbattle.game.player;

/**
 * 玩家业务命令被旧 owner 拒绝时返回的新 owner 位置。
 * 调用方可据此刷新路由缓存或把命令重试到最新 Agent owner。
 */
public record PlayerBusinessRedirect(
        String targetService,
        String targetActor,
        String reason
) {
    public PlayerBusinessRedirect {
        if (targetService == null || targetService.isBlank()) {
            throw new IllegalArgumentException("targetService must not be blank");
        }
        if (targetActor == null || targetActor.isBlank()) {
            throw new IllegalArgumentException("targetActor must not be blank");
        }
        reason = reason == null ? "" : reason;
    }
}
