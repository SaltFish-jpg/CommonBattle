package com.commonbattle.game.player;

import java.util.Map;
import java.util.Objects;

/**
 * 玩家存在未完成异步命令时禁止生成状态快照。
 * 这类异常表示当前保存、迁移或排水应稍后重试，避免把外部 RPC 未回包的半完成状态写入快照。
 */
public final class PendingAsyncPlayerCommandException extends IllegalStateException {
    private final long playerId;
    private final int pendingCommands;
    private final Map<String, Integer> pendingByOperation;

    public PendingAsyncPlayerCommandException(long playerId, int pendingCommands) {
        this(playerId, pendingCommands, Map.of());
    }

    public PendingAsyncPlayerCommandException(
            long playerId,
            int pendingCommands,
            Map<String, Integer> pendingByOperation
    ) {
        super("player " + playerId + " has pending async commands: " + pendingCommands);
        this.playerId = playerId;
        this.pendingCommands = pendingCommands;
        this.pendingByOperation = Map.copyOf(Objects.requireNonNull(pendingByOperation, "pendingByOperation"));
    }

    public long playerId() {
        return playerId;
    }

    public int pendingCommands() {
        return pendingCommands;
    }

    public Map<String, Integer> pendingByOperation() {
        return pendingByOperation;
    }
}
