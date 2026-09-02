package com.commonbattle.game.config;

/**
 * 游戏配置发布结果。
 */
public record GameConfigPublishResult(
        GameConfigPublishStatus status,
        long version,
        GameConfigValidation validation,
        String message
) {
    public static GameConfigPublishResult published(long version) {
        return new GameConfigPublishResult(GameConfigPublishStatus.PUBLISHED, version,
                new GameConfigValidation(java.util.List.of()), "");
    }

    public static GameConfigPublishResult grayPublished(long version) {
        return new GameConfigPublishResult(GameConfigPublishStatus.GRAY_PUBLISHED, version,
                new GameConfigValidation(java.util.List.of()), "");
    }

    public static GameConfigPublishResult rolledBack(long version) {
        return new GameConfigPublishResult(GameConfigPublishStatus.ROLLED_BACK, version,
                new GameConfigValidation(java.util.List.of()), "");
    }

    public static GameConfigPublishResult rejected(long version, GameConfigValidation validation, String message) {
        return new GameConfigPublishResult(GameConfigPublishStatus.REJECTED, version, validation, message);
    }
}
