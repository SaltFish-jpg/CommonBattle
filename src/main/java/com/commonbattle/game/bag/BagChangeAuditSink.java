package com.commonbattle.game.bag;

/**
 * 背包变更流水写入端。
 */
public interface BagChangeAuditSink {
    BagChangeAuditSink NOOP = record -> {
    };

    void record(BagChangeAuditRecord record);
}
