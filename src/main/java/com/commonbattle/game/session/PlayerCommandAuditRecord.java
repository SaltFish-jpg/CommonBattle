package com.commonbattle.game.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 玩家命令审计记录。
 * 用于定位某条玩家请求在灰度、回滚或热更时实际使用的配置版本。
 */
public record PlayerCommandAuditRecord(
        long playerId,
        String sessionId,
        long sessionEpoch,
        long sequence,
        String operation,
        PlayerCommandStatus dispatchStatus,
        PlayerCommandAuditOutcome outcome,
        long configVersion,
        Duration elapsed,
        Instant recordedAt,
        String resultCode,
        String reason
) {
    public PlayerCommandAuditRecord(
            long playerId,
            String sessionId,
            long sessionEpoch,
            long sequence,
            String operation,
            PlayerCommandStatus dispatchStatus,
            PlayerCommandAuditOutcome outcome,
            long configVersion,
            Duration elapsed,
            Instant recordedAt,
            String reason
    ) {
        this(playerId, sessionId, sessionEpoch, sequence, operation, dispatchStatus, outcome, configVersion,
                elapsed, recordedAt, "", reason);
    }

    public PlayerCommandAuditRecord {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(dispatchStatus, "dispatchStatus");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(recordedAt, "recordedAt");
        resultCode = Objects.requireNonNullElse(resultCode, "");
        reason = Objects.requireNonNullElse(reason, "");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (sessionEpoch <= 0) {
            throw new IllegalArgumentException("sessionEpoch must be positive");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (configVersion < 0) {
            throw new IllegalArgumentException("configVersion must not be negative");
        }
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }
}
