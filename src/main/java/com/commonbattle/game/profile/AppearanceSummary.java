package com.commonbattle.game.profile;

import java.util.Objects;

/**
 * 玩家展示外观摘要。
 * 场景、聊天和排行榜只读取摘要，不读取玩家完整养成或装备状态。
 */
public record AppearanceSummary(String avatar, String frame, String costume) {
    public AppearanceSummary() {
        this("", "", "");
    }

    public AppearanceSummary {
        Objects.requireNonNull(avatar, "avatar");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(costume, "costume");
    }

    public static AppearanceSummary defaults() {
        return new AppearanceSummary("avatar_default", "frame_default", "costume_default");
    }
}
