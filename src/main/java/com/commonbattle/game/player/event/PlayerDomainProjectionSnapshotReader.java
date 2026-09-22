package com.commonbattle.game.player.event;

import java.util.Optional;

/**
 * 玩家领域投影快照读取口。
 * 修复线程通过它回源读取 owner 当前可快照化投影，再投递回业务 Actor 邮箱。
 */
public interface PlayerDomainProjectionSnapshotReader {
    Optional<PlayerDomainProjectionSnapshot> find(long playerId);
}
