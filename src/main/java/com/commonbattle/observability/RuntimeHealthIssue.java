package com.commonbattle.observability;

import java.util.Objects;

/**
 * 运行时健康问题。
 * 用稳定 component/code 表达降级原因，便于发布系统、告警和人工排障定位问题来源。
 */
public record RuntimeHealthIssue(
        RuntimeHealthStatus status,
        String component,
        String code,
        long value
) {
    public RuntimeHealthIssue {
        Objects.requireNonNull(status, "status");
        component = Objects.requireNonNull(component, "component").trim();
        code = Objects.requireNonNull(code, "code").trim();
        if (component.isBlank()) {
            throw new IllegalArgumentException("component must not be blank");
        }
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
    }

    public static RuntimeHealthIssue degraded(String component, String code, long value) {
        return new RuntimeHealthIssue(RuntimeHealthStatus.DEGRADED, component, code, value);
    }
}
