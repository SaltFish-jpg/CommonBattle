package com.commonbattle.game.player;

import java.util.Objects;

/**
 * 玩家 Agent 加载结果。
 * 登录、迁移接管和运维诊断可用它区分新建状态与快照恢复状态。
 */
public record PlayerGameAgentHandle(PlayerGameAgent agent, PlayerStateSnapshot snapshot, boolean created) {
    public PlayerGameAgentHandle {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
