package com.commonbattle.game.config;

import java.util.List;
import java.util.Optional;

/**
 * 游戏业务配置注册表。
 * 支持全量发布、灰度发布、按玩家选择版本和回滚到历史版本。
 */
public interface GameConfigRegistry extends GameConfigView {
    GameConfigPublishResult publish(GameConfigPackage config);

    GameConfigPublishResult publishGray(GameConfigPackage config, int percent);

    GameConfigPublishResult rollback(long version);
}
