package com.commonbattle.game.player;

/**
 * 玩家自动保存统计。
 */
public record PlayerAutoSaveStats(long runs, long submitted, long completed, long failedRuns, long failedSaves) {
}
