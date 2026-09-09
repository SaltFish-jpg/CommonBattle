package com.commonbattle.game.session;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

/**
 * 玩家命令序号检查器。
 * 同一 session epoch 内要求命令严格连续，重复命令幂等拒绝，跳号命令不入邮箱。
 */
public final class PlayerCommandSequencer {
    public static final int DEFAULT_RESULT_WINDOW = 10_000;

    private final Map<Key, Long> lastSequences = new ConcurrentHashMap<>();
    private final Map<CommandKey, PlayerCommandStatus> committedStatuses = new HashMap<>();
    private final ArrayDeque<CommandKey> committedOrder = new ArrayDeque<>();
    private final int resultWindow;

    public PlayerCommandSequencer() {
        this(DEFAULT_RESULT_WINDOW);
    }

    public PlayerCommandSequencer(int resultWindow) {
        if (resultWindow <= 0) {
            throw new IllegalArgumentException("resultWindow must be positive");
        }
        this.resultWindow = resultWindow;
    }

    public synchronized PlayerCommandStatus inspect(PlayerCommand command) {
        Objects.requireNonNull(command, "command");
        Key key = new Key(command.playerId(), command.sessionId(), command.sessionEpoch());
        long current = lastSequences.getOrDefault(key, 0L);
        if (command.sequence() <= current) {
            return PlayerCommandStatus.DUPLICATE;
        }
        if (command.sequence() != current + 1) {
            return PlayerCommandStatus.GAP;
        }
        return PlayerCommandStatus.ACCEPTED;
    }

    public synchronized void commit(PlayerCommand command) {
        commit(command, PlayerCommandStatus.ACCEPTED);
    }

    public synchronized void commit(PlayerCommand command, PlayerCommandStatus status) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(status, "status");
        Key key = new Key(command.playerId(), command.sessionId(), command.sessionEpoch());
        lastSequences.put(key, command.sequence());
        remember(command, status);
    }

    public synchronized Optional<PlayerCommandStatus> committedStatus(PlayerCommand command) {
        Objects.requireNonNull(command, "command");
        return Optional.ofNullable(committedStatuses.get(CommandKey.from(command)));
    }

    private void remember(PlayerCommand command, PlayerCommandStatus status) {
        CommandKey key = CommandKey.from(command);
        if (!committedStatuses.containsKey(key)) {
            committedOrder.addLast(key);
        }
        committedStatuses.put(key, status);
        while (committedOrder.size() > resultWindow) {
            CommandKey removed = committedOrder.removeFirst();
            committedStatuses.remove(removed);
        }
    }

    private record Key(long playerId, String sessionId, long epoch) {
    }

    private record CommandKey(long playerId, String sessionId, long epoch, long sequence) {
        private static CommandKey from(PlayerCommand command) {
            return new CommandKey(command.playerId(), command.sessionId(), command.sessionEpoch(), command.sequence());
        }
    }
}
