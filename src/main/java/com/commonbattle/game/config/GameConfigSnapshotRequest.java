package com.commonbattle.game.config;

/**
 * 本地配置缓存向中心服拉取完整快照的请求。
 * knownEventRevision 便于中心侧后续做增量优化，当前实现始终返回完整快照。
 */
public record GameConfigSnapshotRequest(long knownEventRevision) {
    public GameConfigSnapshotRequest {
        if (knownEventRevision < 0) {
            throw new IllegalArgumentException("knownEventRevision must not be negative");
        }
    }
}
