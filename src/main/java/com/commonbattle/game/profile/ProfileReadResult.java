package com.commonbattle.game.profile;

import java.util.Objects;
import java.util.Optional;

/**
 * 玩家基础资料读取结果。
 * profile 可能为空；fresh 表示调用方可以把结果当成本次读取边界上的可信最新快照使用。
 */
public record ProfileReadResult(Optional<CachedProfile> profile, ProfileReadStatus status) {
    public ProfileReadResult {
        profile = Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(status, "status");
    }

    public boolean present() {
        return profile.isPresent();
    }

    public boolean fresh() {
        return profile.filter(cached -> !cached.stale()).isPresent()
                && status != ProfileReadStatus.LOCAL_FALLBACK;
    }

    static ProfileReadResult of(CachedProfile profile, ProfileReadStatus status) {
        return new ProfileReadResult(Optional.of(profile), status);
    }

    static ProfileReadResult empty(ProfileReadStatus status) {
        return new ProfileReadResult(Optional.empty(), status);
    }
}
