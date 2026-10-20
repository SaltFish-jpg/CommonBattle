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
    private final Map<CommandKey, PlayerCommandStatus> inFlightStatuses = new HashMap<>();
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
        if (command.sequence() == current + 1 && inFlightStatuses.containsKey(CommandKey.from(command))) {
            return PlayerCommandStatus.DUPLICATE;
        }
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
        inFlightStatuses.remove(CommandKey.from(command));
        lastSequences.put(key, command.sequence());
        remember(command, status);
    }

    /**
     * 标记命令正在远程 owner 执行；水位不会前移，远程失败后仍允许客户端用同一序号重试。
     */
    public synchronized PlayerCommandStatus beginRemote(PlayerCommand command) {
        Objects.requireNonNull(command, "command");
        PlayerCommandStatus status = inspect(command);
        if (status == PlayerCommandStatus.ACCEPTED) {
            inFlightStatuses.put(CommandKey.from(command), PlayerCommandStatus.ROUTED_REMOTE);
        }
        return status;
    }

    /**
     * 远程 owner 已确认接收命令后推进本地水位，用于非 owner 入口继续接收后续连续命令。
     */
    public synchronized PlayerCommandStatus completeRemote(PlayerCommand command, PlayerCommandStatus finalStatus) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(finalStatus, "finalStatus");
        inFlightStatuses.remove(CommandKey.from(command));
        PlayerCommandStatus status = inspect(command);
        if (status == PlayerCommandStatus.ACCEPTED) {
            commit(command, finalStatus);
        }
        return status;
    }

    /**
     * 远程调用未被 owner 确认接收时清理 in-flight 状态，保留同序号重试空间。
     */
    public synchronized void cancelRemote(PlayerCommand command) {
        Objects.requireNonNull(command, "command");
        inFlightStatuses.remove(CommandKey.from(command));
    }

    public synchronized Optional<PlayerCommandStatus> committedStatus(PlayerCommand command) {
        Objects.requireNonNull(command, "command");
        CommandKey key = CommandKey.from(command);
        PlayerCommandStatus inFlight = inFlightStatuses.get(key);
        if (inFlight != null) {
            return Optional.of(inFlight);
        }
        return Optional.ofNullable(committedStatuses.get(key));
    }

    public synchronized PlayerCommandSequencerSnapshot snapshotForPlayer(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        return new PlayerCommandSequencerSnapshot(
                playerId,
                lastSequences.entrySet().stream()
                        .filter(entry -> entry.getKey().playerId() == playerId)
                        .map(entry -> new PlayerCommandSequencerSnapshot.Watermark(
                                entry.getKey().playerId(),
                                entry.getKey().sessionId(),
                                entry.getKey().epoch(),
                                entry.getValue()
                        ))
                        .toList()
        );
    }

    public synchronized void restore(PlayerCommandSequencerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        for (PlayerCommandSequencerSnapshot.Watermark watermark : snapshot.watermarks()) {
            Key key = new Key(watermark.playerId(), watermark.sessionId(), watermark.sessionEpoch());
            lastSequences.merge(key, watermark.lastSequence(), Math::max);
        }
    }

    public synchronized void forgetPlayer(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        lastSequences.keySet().removeIf(key -> key.playerId() == playerId);
        inFlightStatuses.keySet().removeIf(key -> key.playerId() == playerId);
        committedStatuses.keySet().removeIf(key -> key.playerId() == playerId);
        committedOrder.removeIf(key -> key.playerId() == playerId);
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
