package com.commonbattle.game.config;

/**
 * 配置事件应用结果。
 */
public record GameConfigApplyResult(
        GameConfigApplyStatus status,
        long eventRevision,
        long configVersion,
        String message
) {
    public static GameConfigApplyResult applied(GameConfigChangedEvent event) {
        return new GameConfigApplyResult(GameConfigApplyStatus.APPLIED, event.revision(), event.config().version(), "");
    }

    public static GameConfigApplyResult duplicateOrOld(GameConfigChangedEvent event) {
        return new GameConfigApplyResult(GameConfigApplyStatus.DUPLICATE_OR_OLD, event.revision(),
                event.config().version(), "duplicate_or_old");
    }

    public static GameConfigApplyResult gap(GameConfigChangedEvent event) {
        return new GameConfigApplyResult(GameConfigApplyStatus.GAP, event.revision(), event.config().version(),
                "event_revision_gap");
    }

    public static GameConfigApplyResult rejected(GameConfigChangedEvent event, String message) {
        return new GameConfigApplyResult(GameConfigApplyStatus.REJECTED, event.revision(), event.config().version(),
                message);
    }

    public static GameConfigApplyResult recovered(GameConfigSnapshot snapshot) {
        return new GameConfigApplyResult(GameConfigApplyStatus.RECOVERED, snapshot.eventRevision(),
                snapshot.activeConfig().version(), "");
    }

    public static GameConfigApplyResult recoveryFailed(long currentRevision, String message) {
        return new GameConfigApplyResult(GameConfigApplyStatus.RECOVERY_FAILED, currentRevision, 0, message);
    }
}
