package com.commonbattle.game.session;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家命令序号检查器。
 * 同一 session epoch 内要求命令严格连续，重复命令幂等拒绝，跳号命令不入邮箱。
 */
public final class PlayerCommandSequencer {
    private final Map<Key, Long> lastSequences = new ConcurrentHashMap<>();

    public PlayerCommandStatus inspect(PlayerCommand command) {
        Objects.requireNonNull(command, "command");
        Key key = new Key(command.playerId(), command.sessionId(), command.sessionEpoch());
        long current = lastSequences.getOrDefault(key, 0L);
        if (command.sequence() <= current) {
            return PlayerCommandStatus.DUPLICATE;
        }
        if (command.sequence() != current + 1) {
            return PlayerCommandStatus.GAP;
        }
        lastSequences.put(key, command.sequence());
        return PlayerCommandStatus.ACCEPTED;
    }

    private record Key(long playerId, String sessionId, long epoch) {
    }
}
