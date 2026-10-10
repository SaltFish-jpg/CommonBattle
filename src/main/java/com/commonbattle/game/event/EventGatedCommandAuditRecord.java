package com.commonbattle.game.event;

import java.time.Instant;
import java.util.Objects;

/**
 * 事件门控命令审计记录。
 * 记录跨服事件边界、业务操作和执行阶段，方便排查“事件已到但业务是否真正回到 Actor 执行”。
 */
public record EventGatedCommandAuditRecord(
        String operation,
        String commandType,
        EventGatedCommandBoundary boundary,
        String outcome,
        String result,
        Instant at
) {
    public EventGatedCommandAuditRecord {
        operation = Objects.requireNonNull(operation, "operation");
        commandType = Objects.requireNonNull(commandType, "commandType");
        boundary = Objects.requireNonNull(boundary, "boundary");
        outcome = Objects.requireNonNull(outcome, "outcome");
        result = Objects.requireNonNullElse(result, "");
        at = Objects.requireNonNull(at, "at");
    }
}
