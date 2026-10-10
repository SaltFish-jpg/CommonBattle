package com.commonbattle.game.scene;

import java.util.Objects;
import java.util.Optional;

/**
 * 场景玩家领域投影读取结果。
 * fresh 表示 view 至少达到请求 revision，可参与后续场景结算；非 fresh 只能用于拒绝、等待或触发修复。
 */
public record ScenePlayerDomainReadResult(
        Optional<ScenePlayerDomainView> view,
        ScenePlayerDomainReadStatus status
) {
    public ScenePlayerDomainReadResult {
        view = Objects.requireNonNull(view, "view");
        Objects.requireNonNull(status, "status");
    }

    public boolean fresh() {
        return view.filter(domainView -> !domainView.stale()).isPresent()
                && (status == ScenePlayerDomainReadStatus.LOCAL_HIT
                || status == ScenePlayerDomainReadStatus.REFRESHED);
    }

    public static ScenePlayerDomainReadResult of(
            ScenePlayerDomainView view,
            ScenePlayerDomainReadStatus status
    ) {
        return new ScenePlayerDomainReadResult(Optional.of(view), status);
    }

    public static ScenePlayerDomainReadResult empty(ScenePlayerDomainReadStatus status) {
        return new ScenePlayerDomainReadResult(Optional.empty(), status);
    }
}
