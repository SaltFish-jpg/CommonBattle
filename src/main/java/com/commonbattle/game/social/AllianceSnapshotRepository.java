package com.commonbattle.game.social;

/**
 * 联盟快照仓库。
 * 生产环境可实现为联盟 owner DB + Redis 写穿透，读取方通常只在 replay 缺口或强一致校验时回源。
 */
public interface AllianceSnapshotRepository extends AllianceSnapshotReader {
    void save(AllianceSnapshot snapshot);
}
