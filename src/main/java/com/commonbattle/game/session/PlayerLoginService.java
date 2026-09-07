package com.commonbattle.game.session;

import com.commonbattle.game.player.PlayerGameAgentHandle;
import com.commonbattle.game.player.PlayerGameAgentManager;
import com.commonbattle.game.player.PlayerStateSnapshot;
import com.commonbattle.runtime.DrainableComponent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 玩家登录入口服务。
 * 它先恢复并激活玩家 Agent，再绑定当前 session，保证后续网关命令进入 Dispatcher 时已有可路由 owner。
 */
public final class PlayerLoginService implements DrainableComponent {
    private final PlayerGameAgentManager agents;
    private final PlayerSessionRegistry sessions;
    private final AtomicBoolean draining = new AtomicBoolean();

    public PlayerLoginService(PlayerGameAgentManager agents, PlayerSessionRegistry sessions) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public PlayerLoginResult login(long playerId, String sessionId) {
        if (draining.get()) {
            throw new PlayerLoginDrainingException(playerId);
        }
        PlayerGameAgentHandle handle = agents.load(playerId);
        PlayerSession session = sessions.bind(playerId, sessionId);
        return new PlayerLoginResult(
                session,
                handle.created(),
                handle.snapshot().revision(),
                handle.snapshot().eventRevision(),
                handle.agent().profile().createdAt()
        );
    }

    public void logout(PlayerSession session) {
        sessions.unbind(session);
    }

    public boolean logoutAndPassivate(PlayerSession session, Consumer<PlayerStateSnapshot> callback) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(callback, "callback");
        if (!sessions.isCurrent(session.playerId(), session.sessionId(), session.epoch())) {
            return false;
        }
        sessions.unbind(session);
        return agents.passivateAndSave(session.playerId(), callback);
    }

    @Override
    public void beginDrain() {
        draining.set(true);
    }

    @Override
    public void resumeAccepting() {
        draining.set(false);
    }

    @Override
    public boolean isDraining() {
        return draining.get();
    }
}
