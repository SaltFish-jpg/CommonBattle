package com.commonbattle.game.config;

/**
 * 本地配置缓存发现事件缺口后的恢复触发器。
 * 实现不得在事件回调线程里阻塞等待恢复完成。
 */
@FunctionalInterface
public interface GameConfigRecoveryTrigger {
    void onGap(GameConfigApplyResult gapResult);
}
