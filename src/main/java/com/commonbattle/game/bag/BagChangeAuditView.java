package com.commonbattle.game.bag;

/**
 * 背包变更流水查询视图。
 */
public interface BagChangeAuditView {
    BagChangeAuditPage query(BagChangeAuditQuery query);

    BagChangeAuditStats stats();
}
