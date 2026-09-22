package com.commonbattle.game.player;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.migration.AgentMigrationAcceptRequest;
import com.commonbattle.actor.agent.migration.AgentMigrationRestoreHandler;

import java.util.Objects;

/**
 * 玩家 Agent 迁入恢复器。
 * 该方法由目标 Actor 邮箱执行，恢复后的玩家业务仍保持单邮箱串行语义。
 */
public final class PlayerAgentMigrationRestoreHandler implements AgentMigrationRestoreHandler {
    private final PlayerGameAgentManager agents;
    private final PlayerStateSnapshotSerializer serializer;

    public PlayerAgentMigrationRestoreHandler(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer
    ) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public void restore(AgentMigrationAcceptRequest request, ActorContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        if (!AgentIdentity.PLAYER.equals(request.identity().type())) {
            throw new IllegalArgumentException("unsupported migration identity: " + request.identity().type());
        }
        if (!PlayerAgentMigrationStatePacker.STATE_TYPE.equals(request.stateType())) {
            throw new IllegalArgumentException("unsupported player migration state type: " + request.stateType());
        }
        PlayerStateSnapshot snapshot = serializer.deserialize(request.stateBytes());
        long expectedPlayerId = parsePlayerId(request.identity());
        if (snapshot.playerId() != expectedPlayerId) {
            throw new IllegalArgumentException("player migration snapshot identity mismatch");
        }
        agents.restoreMigrated(snapshot, context.self());
    }

    private static long parsePlayerId(AgentIdentity identity) {
        try {
            return Long.parseLong(identity.key());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid player id: " + identity.key(), e);
        }
    }
}
