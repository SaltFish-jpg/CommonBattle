package com.commonbattle.game.session;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存玩家会话注册表。
 * 适合单进程网关和单元测试；生产可替换为网关内存 + 登录中心仲裁。
 */
public final class InMemoryPlayerSessionRegistry implements PlayerSessionRegistry {
    private final Clock clock;
    private final Map<Long, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> epochs = new ConcurrentHashMap<>();

    public InMemoryPlayerSessionRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PlayerSession bind(long playerId, String sessionId) {
        long epoch = epochs.computeIfAbsent(playerId, ignored -> new AtomicLong()).incrementAndGet();
        PlayerSession session = new PlayerSession(playerId, sessionId, epoch, clock.instant());
        sessions.put(playerId, session);
        return session;
    }

    @Override
    public Optional<PlayerSession> current(long playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    @Override
    public boolean isCurrent(long playerId, String sessionId, long epoch) {
        return current(playerId)
                .filter(session -> session.sessionId().equals(sessionId))
                .filter(session -> session.epoch() == epoch)
                .isPresent();
    }

    @Override
    public void unbind(PlayerSession session) {
        Objects.requireNonNull(session, "session");
        sessions.remove(session.playerId(), session);
    }
}
