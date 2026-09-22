package com.commonbattle.observability;

import java.util.Objects;

/**
 * 运维 HTTP 的轻量访问控制配置。
 * 配置 admin token 后，具备副作用或敏感排障信息的端点会要求请求头携带相同 token。
 */
public record OpsHttpSecurityConfig(
        String adminToken,
        String tokenHeader,
        String operatorHeader
) {
    public static final String DEFAULT_TOKEN_HEADER = "X-CommonBattle-Ops-Token";
    public static final String DEFAULT_OPERATOR_HEADER = "X-CommonBattle-Ops-Operator";

    public OpsHttpSecurityConfig {
        adminToken = Objects.requireNonNullElse(adminToken, "").trim();
        tokenHeader = normalizeHeader(tokenHeader, DEFAULT_TOKEN_HEADER, "tokenHeader");
        operatorHeader = normalizeHeader(operatorHeader, DEFAULT_OPERATOR_HEADER, "operatorHeader");
    }

    public static OpsHttpSecurityConfig disabled() {
        return new OpsHttpSecurityConfig("", DEFAULT_TOKEN_HEADER, DEFAULT_OPERATOR_HEADER);
    }

    public boolean enabled() {
        return !adminToken.isBlank();
    }

    private static String normalizeHeader(String value, String defaultValue, String field) {
        String normalized = Objects.requireNonNullElse(value, defaultValue).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
