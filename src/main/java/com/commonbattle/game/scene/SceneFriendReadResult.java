package com.commonbattle.game.scene;

import java.util.Objects;
import java.util.Optional;

/**
 * 场景好友快照读取结果。
 * fresh 表示调用方可以把 view 当成至少达到请求 revision 的可信快照；非 fresh 只能作为缺口或降级信号。
 */
public record SceneFriendReadResult(Optional<ScenePlayerFriendView> view, SceneFriendReadStatus status) {
    public SceneFriendReadResult {
        view = Objects.requireNonNull(view, "view");
        Objects.requireNonNull(status, "status");
    }

    public boolean fresh() {
        return view.filter(friendView -> !friendView.stale()).isPresent()
                && (status == SceneFriendReadStatus.LOCAL_HIT || status == SceneFriendReadStatus.REFRESHED);
    }

    public static SceneFriendReadResult of(ScenePlayerFriendView view, SceneFriendReadStatus status) {
        return new SceneFriendReadResult(Optional.of(view), status);
    }

    public static SceneFriendReadResult empty(SceneFriendReadStatus status) {
        return new SceneFriendReadResult(Optional.empty(), status);
    }
}
