package com.commonbattle.game.player;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandHandler;

import java.util.Objects;
import java.util.function.LongFunction;

/**
 * PlayerCommandDispatcher 的通用业务处理器。
 * Dispatcher 完成 session、序号、限流和本地路由后，本处理器在玩家邮箱内执行业务命令。
 */
public final class PlayerBusinessCommandHandler implements PlayerCommandHandler {
    private final LongFunction<PlayerGameAgent> agents;
    private final PlayerBusinessResultSink results;

    public PlayerBusinessCommandHandler(LongFunction<PlayerGameAgent> agents) {
        this(agents, PlayerBusinessResultSink.NOOP);
    }

    public PlayerBusinessCommandHandler(LongFunction<PlayerGameAgent> agents, PlayerBusinessResultSink results) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.results = Objects.requireNonNull(results, "results");
    }

    @Override
    public void handle(ActorContext context, PlayerCommand command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");
        try {
            if (!(command.payload() instanceof PlayerBusinessCommand<?> businessCommand)) {
                throw new IllegalArgumentException("payload must be PlayerBusinessCommand");
            }
            if (!command.operation().equals(businessCommand.operation())) {
                throw new IllegalArgumentException("player command operation does not match payload");
            }
            PlayerGameAgent agent = Objects.requireNonNull(agents.apply(command.playerId()), "player agent");
            Object response = agent.executeBusiness(businessCommand);
            results.completed(command, PlayerBusinessResponse.success(command, response));
        } catch (RuntimeException | Error e) {
            results.completed(command, PlayerBusinessResponse.failure(command, e));
            throw e;
        }
    }
}
