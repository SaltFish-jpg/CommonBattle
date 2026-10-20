package com.commonbattle.cluster.event;

/**
 * 中心事件日志的原子追加与历史裁剪结果。
 */
public record ClusterEventJournalAppendResult(
        ClusterEventJournalAppendStatus status,
        int trimmedEvents
) {
    public ClusterEventJournalAppendResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (trimmedEvents < 0) {
            throw new IllegalArgumentException("trimmedEvents must not be negative");
        }
    }
}
