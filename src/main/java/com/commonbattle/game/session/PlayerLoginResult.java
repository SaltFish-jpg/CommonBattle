package com.commonbattle.game.session;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家登录装载结果。
 * 网关可把 session epoch 返回给客户端，Game 服可用恢复版本做登录审计和迁移诊断。
 */
public record PlayerLoginResult(
        PlayerSession session,
        boolean created,
        long stateRevision,
        long eventRevision,
        Instant profileCreatedAt
) {
    public PlayerLoginResult {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(profileCreatedAt, "profileCreatedAt");
        if (stateRevision < 0) {
            throw new IllegalArgumentException("stateRevision must not be negative");
        }
        if (eventRevision < 0) {
            throw new IllegalArgumentException("eventRevision must not be negative");
        }
    }
}
