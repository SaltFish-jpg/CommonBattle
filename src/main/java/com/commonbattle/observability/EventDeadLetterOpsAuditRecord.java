package com.commonbattle.observability;

import java.time.Instant;
import java.util.Objects;

/**
 * 事件死信人工操作审计记录。
 */
public record EventDeadLetterOpsAuditRecord(
        String action,
        String target,
        String topic,
        String identity,
        boolean redriven,
        Instant at,
        String remote,
        String operator
) {
    public EventDeadLetterOpsAuditRecord {
        action = Objects.requireNonNull(action, "action");
        target = Objects.requireNonNull(target, "target");
        topic = Objects.requireNonNull(topic, "topic");
        identity = Objects.requireNonNull(identity, "identity");
        at = Objects.requireNonNull(at, "at");
        remote = Objects.requireNonNull(remote, "remote");
        operator = Objects.requireNonNullElse(operator, "");
    }
}
