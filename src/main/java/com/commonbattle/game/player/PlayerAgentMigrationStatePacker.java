package com.commonbattle.game.player;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.migration.AgentMigrationSnapshot;
import com.commonbattle.actor.agent.migration.AgentMigrationStatePacker;

import java.util.Objects;

/**
 * 玩家 Agent 迁移快照打包器。
 * 打包动作由迁移协调器投递到源玩家邮箱内执行，因此可以直接读取玩家聚合并得到一致快照。
 */
public final class PlayerAgentMigrationStatePacker implements AgentMigrationStatePacker {
    public static final String STATE_TYPE = "commonbattle.player.state.v1";

    private final PlayerGameAgentManager agents;
    private final PlayerStateSnapshotSerializer serializer;

    public PlayerAgentMigrationStatePacker(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer
    ) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public AgentMigrationSnapshot pack(AgentIdentity identity, AgentLocation target, ActorContext context) {
        if (!AgentIdentity.PLAYER.equals(identity.type())) {
            throw new IllegalArgumentException("unsupported migration identity: " + identity.type());
        }
        long playerId = parsePlayerId(identity);
        PlayerGameAgent agent = agents.get(playerId)
                .orElseThrow(() -> new IllegalStateException("player agent not loaded: " + playerId));
        PlayerStateSnapshot snapshot = agent.exportForMigrationInCurrentMailbox();
        return new AgentMigrationSnapshot(STATE_TYPE, serializer.serialize(snapshot));
    }

    private static long parsePlayerId(AgentIdentity identity) {
        try {
            return Long.parseLong(identity.key());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid player id: " + identity.key(), e);
        }
    }
}
