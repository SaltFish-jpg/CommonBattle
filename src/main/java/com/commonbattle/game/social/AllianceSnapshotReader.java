package com.commonbattle.game.social;

import java.util.Optional;

/**
 * 联盟快照读取入口。
 * Scene、Chat 等服务在联盟关系事件 replay 缺口时，通过它回联盟 owner 读取最新成员状态。
 */
public interface AllianceSnapshotReader {
    Optional<AllianceSnapshot> find(long allianceId);
}
