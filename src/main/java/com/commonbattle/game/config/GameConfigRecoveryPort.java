package com.commonbattle.game.config;

import java.util.function.Consumer;

/**
 * 配置完整快照恢复入口。
 * 实现通常通过 RPC 向中心服拉取快照，再应用到本地配置缓存。
 */
@FunctionalInterface
public interface GameConfigRecoveryPort {
    void recover(Consumer<GameConfigApplyResult> callback);
}
