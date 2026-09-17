package com.commonbattle.game.event;

import java.util.List;

/**
 * owner 投影修复隔离队列的运维控制口。
 * 运维端可读取当前隔离 owner，并手动释放 owner 回到普通修复队列。
 */
public interface OwnerEventRepairIsolationAdmin {
    List<OwnerEventRepairIsolatedOwner> isolatedOwners();

    int releaseIsolatedOwner(String ownerKey);

    int releaseAllIsolatedOwners();
}
