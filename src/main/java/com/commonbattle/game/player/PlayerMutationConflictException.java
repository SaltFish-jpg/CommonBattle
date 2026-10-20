package com.commonbattle.game.player;

/**
 * 玩家写入因 owner fencing 或 revision 冲突被拒绝。
 * 捕获方必须停止旧 Agent，不能把该异常当作普通业务失败继续重试。
 */
public final class PlayerMutationConflictException extends IllegalStateException {
    private final long playerId;
    private final long attemptedRevision;
    private final long currentRevision;
    private final PlayerMutationConflictReason reason;

    public PlayerMutationConflictException(
            long playerId,
            long attemptedRevision,
            long currentRevision,
            PlayerMutationConflictReason reason
    ) {
        super("player mutation conflict: playerId=" + playerId
                + ", attemptedRevision=" + attemptedRevision
                + ", currentRevision=" + currentRevision
                + ", reason=" + reason);
        this.playerId = playerId;
        this.attemptedRevision = attemptedRevision;
        this.currentRevision = currentRevision;
        this.reason = reason;
    }

    public long playerId() {
        return playerId;
    }

    public long attemptedRevision() {
        return attemptedRevision;
    }

    public long currentRevision() {
        return currentRevision;
    }

    public PlayerMutationConflictReason reason() {
        return reason;
    }
}
