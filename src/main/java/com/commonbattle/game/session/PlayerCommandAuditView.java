package com.commonbattle.game.session;

import java.util.List;
import java.util.Objects;

/**
 * 玩家命令审计只读视图。
 * 健康探针和运维查询只读取审计快照，不参与命令执行链路。
 */
public interface PlayerCommandAuditView {
    List<PlayerCommandAuditRecord> records();

    default PlayerCommandAuditPage query(PlayerCommandAuditQuery query) {
        Objects.requireNonNull(query, "query");
        List<PlayerCommandAuditRecord> matched = records().stream()
                .filter(record -> query.playerId() == null || query.playerId() == record.playerId())
                .filter(record -> query.operation() == null || query.operation().equals(record.operation()))
                .filter(record -> query.dispatchStatus() == null || query.dispatchStatus() == record.dispatchStatus())
                .filter(record -> query.outcome() == null || query.outcome() == record.outcome())
                .toList();
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return new PlayerCommandAuditPage(matched.subList(from, to), matched.size(), query.offset(), query.limit());
    }

    default PlayerCommandAuditStats stats() {
        return new PlayerCommandAuditStats(records().size(), 0);
    }
}
