package com.commonbattle.game.session;

import java.util.List;

/**
 * 玩家命令审计只读视图。
 * 健康探针和运维查询只读取审计快照，不参与命令执行链路。
 */
public interface PlayerCommandAuditView {
    List<PlayerCommandAuditRecord> records();

    default PlayerCommandAuditStats stats() {
        return new PlayerCommandAuditStats(records().size(), 0);
    }
}
