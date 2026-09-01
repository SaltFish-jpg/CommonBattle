package com.commonbattle.game.profile;

import java.util.Optional;

/**
 * 玩家基础资料快照读取入口。
 * 场景、聊天等服务在本地 cache 跳号或启动补偿时使用它读取最新快照。
 */
public interface ProfileSnapshotReader {
    Optional<PlayerProfileSnapshot> find(long playerId);
}
