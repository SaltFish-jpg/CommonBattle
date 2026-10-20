package com.commonbattle.game.player;

/**
 * 玩家仍有未完成网关响应时拒绝迁移，防止回调在源进程完成而结果缓存未进入迁移快照。
 */
public final class PendingPlayerBusinessResponseException extends IllegalStateException {
    private final long playerId;
    private final int pendingResponses;

    public PendingPlayerBusinessResponseException(long playerId, int pendingResponses) {
        super("player " + playerId + " has pending business responses: " + pendingResponses);
        this.playerId = playerId;
        this.pendingResponses = pendingResponses;
    }

    public long playerId() {
        return playerId;
    }

    public int pendingResponses() {
        return pendingResponses;
    }
}
