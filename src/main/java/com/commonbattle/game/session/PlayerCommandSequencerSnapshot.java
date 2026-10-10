package com.commonbattle.game.session;

import java.util.List;
import java.util.Objects;

/**
 * 玩家命令序号水位快照。
 * 玩家迁移时携带该快照，目标 Game 服恢复后才能继续拒绝源服已经接收过的旧命令。
 */
public record PlayerCommandSequencerSnapshot(
        long playerId,
        List<Watermark> watermarks
) {
    public PlayerCommandSequencerSnapshot {
        Objects.requireNonNull(watermarks, "watermarks");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        watermarks = List.copyOf(watermarks);
        for (Watermark watermark : watermarks) {
            Objects.requireNonNull(watermark, "watermark");
            if (watermark.playerId() != playerId) {
                throw new IllegalArgumentException("watermark playerId mismatch");
            }
        }
    }

    public static PlayerCommandSequencerSnapshot empty(long playerId) {
        return new PlayerCommandSequencerSnapshot(playerId, List.of());
    }

    /**
     * 单个 session epoch 的最后接收序号。
     */
    public record Watermark(long playerId, String sessionId, long sessionEpoch, long lastSequence) {
        public Watermark {
            Objects.requireNonNull(sessionId, "sessionId");
            if (playerId <= 0) {
                throw new IllegalArgumentException("playerId must be positive");
            }
            if (sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
            if (sessionEpoch <= 0) {
                throw new IllegalArgumentException("sessionEpoch must be positive");
            }
            if (lastSequence <= 0) {
                throw new IllegalArgumentException("lastSequence must be positive");
            }
        }
    }
}
