package com.commonbattle.game.activity;

/**
 * 单个活动进度的可持久化快照。
 */
public record ActivityProgressSnapshot(int value, boolean claimed) {
}
