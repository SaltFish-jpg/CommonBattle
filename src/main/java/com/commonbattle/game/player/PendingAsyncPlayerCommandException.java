package com.commonbattle.game.player;

/**
 * 玩家存在未完成异步命令时禁止生成状态快照。
 * 这类异常表示当前保存、迁移或排水应稍后重试，避免把外部 RPC 未回包的半完成状态写入快照。
 */
public final class PendingAsyncPlayerCommandException extends IllegalStateException {
    public PendingAsyncPlayerCommandException(long playerId, int pendingCommands) {
        super("player " + playerId + " has pending async commands: " + pendingCommands);
    }
}
