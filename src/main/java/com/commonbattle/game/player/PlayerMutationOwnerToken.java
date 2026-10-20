package com.commonbattle.game.player;

import com.commonbattle.actor.agent.AgentDirectoryLease;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * 玩家原子提交使用的目录 fencing token 编码。
 * 结构化 token 只允许单调升级，防止旧进程在重新执行 owner 获取时覆盖新 owner。
 */
final class PlayerMutationOwnerToken {
    private static final String PREFIX = "agent-lease:";

    private PlayerMutationOwnerToken() {
    }

    static String encode(AgentDirectoryLease lease) {
        Objects.requireNonNull(lease, "lease");
        String session = Base64.getUrlEncoder().withoutPadding().encodeToString(
                lease.ownerSession().getBytes(StandardCharsets.UTF_8)
        );
        return PREFIX + lease.fencingToken() + ":" + session;
    }

    static Optional<Decoded> decode(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.startsWith(PREFIX)) {
            return Optional.empty();
        }
        int separator = value.indexOf(':', PREFIX.length());
        if (separator < 0 || separator == value.length() - 1) {
            throw new IllegalStateException("Invalid player mutation owner token");
        }
        try {
            long fencingToken = Long.parseLong(value.substring(PREFIX.length(), separator));
            if (fencingToken <= 0) {
                throw new IllegalStateException("Invalid player mutation fencing token");
            }
            String ownerSession = new String(
                    Base64.getUrlDecoder().decode(value.substring(separator + 1)),
                    StandardCharsets.UTF_8
            );
            if (ownerSession.isBlank()) {
                throw new IllegalStateException("Invalid player mutation owner session");
            }
            return Optional.of(new Decoded(fencingToken, ownerSession));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid player mutation owner token", e);
        }
    }

    record Decoded(long fencingToken, String ownerSession) {
    }
}
