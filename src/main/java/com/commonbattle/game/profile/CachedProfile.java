package com.commonbattle.game.profile;

/**
 * 本地资料缓存项。
 * stale 表示订阅事件发生跳号或本地发现版本不可信，关键读需要回源刷新。
 */
public record CachedProfile(PlayerProfileSnapshot snapshot, boolean stale) {
}
