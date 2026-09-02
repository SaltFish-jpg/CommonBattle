package com.commonbattle.game.config;

import java.util.List;
import java.util.Optional;

/**
 * 游戏配置只读视图。
 * Game、Scene、Chat 等业务进程应依赖该接口读取本地配置缓存，只有中心配置服务负责发布和回滚。
 */
public interface GameConfigView {
    GameConfigRuntime active();

    GameConfigRuntime resolve(long playerId);

    Optional<GameConfigRuntime> version(long version);

    List<Long> versions();
}
