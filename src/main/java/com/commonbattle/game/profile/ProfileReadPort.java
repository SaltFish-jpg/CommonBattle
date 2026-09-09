package com.commonbattle.game.profile;

import java.util.Optional;

/**
 * 玩家基础资料读取端口。
 * Game、Scene、Chat 等业务服务应依赖该接口读取本地快照，并在事件驱动的后续逻辑中用 readAtLeast 保证不读旧版本。
 */
public interface ProfileReadPort {
    ProfileReadResult read(long playerId, ProfileReadMode mode);

    ProfileReadResult readAtLeast(long playerId, long minimumRevision);

    default Optional<PlayerProfileSnapshot> freshSnapshot(long playerId, long minimumRevision) {
        ProfileReadResult result = readAtLeast(playerId, minimumRevision);
        return result.fresh() ? result.profile().map(CachedProfile::snapshot) : Optional.empty();
    }
}
