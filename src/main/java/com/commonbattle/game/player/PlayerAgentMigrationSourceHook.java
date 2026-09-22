package com.commonbattle.game.player;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.migration.AgentMigrationSourceHook;
import com.commonbattle.actor.agent.migration.AgentMigrationTask;

import java.util.Objects;

/**
 * 玩家 Agent 源端迁移清理。
 * 目录切到目标后释放旧 Game 服内存句柄和定时器；如果目标接收失败回滚，再用迁移快照恢复旧 owner。
 */
public final class PlayerAgentMigrationSourceHook implements AgentMigrationSourceHook {
    private final PlayerGameAgentManager agents;
    private final PlayerStateSnapshotSerializer serializer;

    public PlayerAgentMigrationSourceHook(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer
    ) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public void sourceMoved(AgentMigrationTask task) {
        if (!AgentIdentity.PLAYER.equals(task.identity().type())) {
            return;
        }
        agents.removeMigrated(parsePlayerId(task));
    }

    @Override
    public void rollbackRestored(AgentMigrationTask task) {
        if (!AgentIdentity.PLAYER.equals(task.identity().type())) {
            return;
        }
        long playerId = parsePlayerId(task);
        if (agents.handle(playerId).isPresent()) {
            return;
        }
        if (!PlayerAgentMigrationStatePacker.STATE_TYPE.equals(task.snapshot().stateType())) {
            throw new IllegalArgumentException("unsupported player migration state type: "
                    + task.snapshot().stateType());
        }
        PlayerStateSnapshot snapshot = serializer.deserialize(task.snapshot().stateBytes());
        if (snapshot.playerId() != playerId) {
            throw new IllegalArgumentException("player migration snapshot identity mismatch");
        }
        agents.restoreMigrated(snapshot, task.source().actorRef());
    }

    private static long parsePlayerId(AgentMigrationTask task) {
        try {
            return Long.parseLong(task.identity().key());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid player id: " + task.identity().key(), e);
        }
    }
}
