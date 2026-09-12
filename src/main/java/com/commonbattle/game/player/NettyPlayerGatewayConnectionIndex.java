package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerSession;
import io.netty.channel.Channel;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家网关当前连接索引。
 * 它只服务连接治理，不参与玩家业务状态判定；真正的会话有效性仍由 PlayerSessionRegistry 的 epoch 决定。
 */
final class NettyPlayerGatewayConnectionIndex {
    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();

    boolean hasActive(long playerId) {
        Connection current = connections.get(playerId);
        return current != null && current.channel().isOpen();
    }

    Channel bind(PlayerSession session, Channel channel) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(channel, "channel");
        Connection previous = connections.put(session.playerId(), new Connection(session, channel));
        if (previous == null || previous.channel() == channel || !previous.channel().isOpen()) {
            return null;
        }
        return previous.channel();
    }

    void unbind(PlayerSession session, Channel channel) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(channel, "channel");
        connections.remove(session.playerId(), new Connection(session, channel));
    }

    private record Connection(PlayerSession session, Channel channel) {
    }
}
