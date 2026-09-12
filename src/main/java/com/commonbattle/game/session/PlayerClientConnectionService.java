package com.commonbattle.game.session;

import com.commonbattle.game.player.PlayerStateSnapshot;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 玩家客户端连接生命周期服务。
 * 它串起登录装载、session 绑定、Netty 写出器绑定和离线消息刷新，网关层无需直接操作这些组件。
 */
public final class PlayerClientConnectionService {
    public static final int DEFAULT_OFFLINE_FLUSH_LIMIT = 256;

    private final PlayerLoginService logins;
    private final PlayerOutboundDeliveryHub outbound;
    private final int offlineFlushLimit;

    public PlayerClientConnectionService(PlayerLoginService logins, PlayerOutboundDeliveryHub outbound) {
        this(logins, outbound, DEFAULT_OFFLINE_FLUSH_LIMIT);
    }

    public PlayerClientConnectionService(
            PlayerLoginService logins,
            PlayerOutboundDeliveryHub outbound,
            int offlineFlushLimit
    ) {
        this.logins = Objects.requireNonNull(logins, "logins");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        if (offlineFlushLimit <= 0) {
            throw new IllegalArgumentException("offlineFlushLimit must be positive");
        }
        this.offlineFlushLimit = offlineFlushLimit;
    }

    public PlayerClientConnectionResult connect(long playerId, String sessionId, PlayerOutboundWriter writer) {
        Objects.requireNonNull(writer, "writer");
        PlayerLoginResult login = logins.login(playerId, sessionId);
        if (!outbound.connect(login.session(), writer)) {
            throw new IllegalStateException("failed to bind current player outbound session");
        }
        PlayerOutboundDeliveryResult pendingAckFlush = outbound.flushPendingAck(login.session(), offlineFlushLimit);
        PlayerOutboundDeliveryResult offlineFlush = outbound.flushOffline(login.session(), offlineFlushLimit);
        return new PlayerClientConnectionResult(login, pendingAckFlush.plus(offlineFlush));
    }

    public long acknowledge(PlayerSession session, long acknowledgedSequence) {
        Objects.requireNonNull(session, "session");
        return outbound.acknowledge(session, acknowledgedSequence);
    }

    public PlayerOutboundAckStatus ackStatus(PlayerSession session, int maxPendingAckMessages, java.time.Duration maxPendingAckAge) {
        Objects.requireNonNull(session, "session");
        return outbound.ackStatus(session, maxPendingAckMessages, maxPendingAckAge);
    }

    public void disconnect(PlayerSession session) {
        Objects.requireNonNull(session, "session");
        outbound.disconnect(session);
        logins.logout(session);
    }

    public boolean disconnectAndPassivate(PlayerSession session, Consumer<PlayerStateSnapshot> callback) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(callback, "callback");
        outbound.disconnect(session);
        return logins.logoutAndPassivate(session, callback);
    }
}
