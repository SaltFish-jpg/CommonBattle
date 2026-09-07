package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommand;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家业务响应等待中心。
 * 它把玩家邮箱内产生的结果路由给等待中的本服调用方或跨服 RPC responder。
 */
public final class PlayerBusinessResponseHub implements PlayerBusinessResultSink {
    private final Map<Key, PlayerBusinessResponseCallback> callbacks = new ConcurrentHashMap<>();
    private final PlayerBusinessResultSink fallback;

    public PlayerBusinessResponseHub() {
        this(PlayerBusinessResultSink.NOOP);
    }

    public PlayerBusinessResponseHub(PlayerBusinessResultSink fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    public PlayerBusinessResponseRegistration expect(
            PlayerCommand command,
            PlayerBusinessResponseCallback callback
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(callback, "callback");
        Key key = Key.from(command);
        PlayerBusinessResponseCallback previous = callbacks.putIfAbsent(key, callback);
        if (previous != null) {
            throw new IllegalStateException("Duplicate player business response waiter: " + key);
        }
        return () -> callbacks.remove(key, callback);
    }

    public int pendingResponses() {
        return callbacks.size();
    }

    @Override
    public void completed(PlayerCommand command, PlayerBusinessResponse response) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(response, "response");
        PlayerBusinessResponseCallback callback = callbacks.remove(Key.from(command));
        if (callback != null) {
            callback.completed(response);
            return;
        }
        fallback.completed(command, response);
    }

    @Override
    public void succeeded(PlayerCommand command, Object response) {
        completed(command, PlayerBusinessResponse.success(command, response));
    }

    @Override
    public void failed(PlayerCommand command, Throwable error) {
        completed(command, PlayerBusinessResponse.failure(command, error));
    }

    private record Key(long playerId, String sessionId, long sessionEpoch, long sequence) {
        private static Key from(PlayerCommand command) {
            return new Key(command.playerId(), command.sessionId(), command.sessionEpoch(), command.sequence());
        }
    }
}
