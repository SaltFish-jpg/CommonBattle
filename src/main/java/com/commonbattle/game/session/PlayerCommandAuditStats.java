package com.commonbattle.game.session;

/**
 * 玩家命令审计窗口统计。
 * retained 表示当前仍可查询的记录数，dropped 表示因为窗口容量被淘汰的历史记录数。
 */
public record PlayerCommandAuditStats(long retained, long dropped) {
    public PlayerCommandAuditStats {
        if (retained < 0 || dropped < 0) {
            throw new IllegalArgumentException("audit stats must not be negative");
        }
    }
}
