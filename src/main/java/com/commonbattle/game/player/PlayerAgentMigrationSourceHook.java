package com.commonbattle.game.player;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.migration.AgentMigrationSourceHook;
import com.commonbattle.actor.agent.migration.AgentMigrationTask;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.session.PlayerCommandSequencer;

import java.util.Objects;

/**
 * 玩家 Agent 源端迁移清理。
 * 目录切到目标后释放旧 Game 服内存句柄和定时器；如果目标接收失败回滚，再用迁移快照恢复旧 owner。
 */
public final class PlayerAgentMigrationSourceHook implements AgentMigrationSourceHook {
    private final PlayerGameAgentManager agents;
    private final PlayerStateSnapshotSerializer serializer;
    private final PlayerCommandSequencer commandSequencer;
    private final PlayerBusinessResponseHub businessResponses;
    private final PlayerAgentMigrationPayloadCodec migrationCodec;

    public PlayerAgentMigrationSourceHook(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer
    ) {
        this(agents, serializer, null);
    }

    public PlayerAgentMigrationSourceHook(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer,
            PlayerCommandSequencer commandSequencer
    ) {
        this(agents, serializer, commandSequencer, null, null);
    }

    public PlayerAgentMigrationSourceHook(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer,
            PlayerCommandSequencer commandSequencer,
            PlayerBusinessResponseHub businessResponses,
            PayloadCodecRegistry payloadCodecs
    ) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.commandSequencer = commandSequencer;
        this.businessResponses = businessResponses;
        this.migrationCodec = payloadCodecs == null
                ? null
                : new PlayerAgentMigrationPayloadCodec(serializer, payloadCodecs);
        if ((businessResponses == null) != (payloadCodecs == null)) {
            throw new IllegalArgumentException("businessResponses and payloadCodecs must be configured together");
        }
    }

    @Override
    public void sourceMoved(AgentMigrationTask task) {
        if (!AgentIdentity.PLAYER.equals(task.identity().type())) {
            return;
        }
        long playerId = parsePlayerId(task);
        agents.removeMigrated(playerId);
        if (commandSequencer != null) {
            commandSequencer.forgetPlayer(playerId);
        }
        if (businessResponses != null) {
            businessResponses.forgetPlayer(playerId);
        }
    }

    @Override
    public void rollbackRestored(AgentMigrationTask task) {
        if (!AgentIdentity.PLAYER.equals(task.identity().type())) {
            return;
        }
        long playerId = parsePlayerId(task);
        if (!PlayerAgentMigrationStatePacker.STATE_TYPE.equals(task.snapshot().stateType())
                && !PlayerAgentMigrationStatePacker.RUNTIME_STATE_TYPE.equals(task.snapshot().stateType())) {
            throw new IllegalArgumentException("unsupported player migration state type: "
                    + task.snapshot().stateType());
        }
        PlayerAgentMigrationPayload payload = decode(task);
        PlayerStateSnapshot snapshot = payload.state();
        if (snapshot.playerId() != playerId) {
            throw new IllegalArgumentException("player migration snapshot identity mismatch");
        }
        PlayerGameAgentHandle restored = agents.handle(playerId).isPresent()
                ? agents.reclaimAfterMigrationRollback(snapshot)
                : agents.restoreMigrated(snapshot, task.source().actorRef());
        PlayerStateSnapshot authoritative = restored.snapshot();
        if (commandSequencer != null) {
            commandSequencer.restore(authoritative.commandSequencer());
        }
        if (businessResponses != null) {
            businessResponses.restore(agents.migrationBusinessResponses(
                    authoritative.playerId(), payload.completedResponses()
            ));
            businessResponses.checkpoint(authoritative.playerId());
        }
    }

    private PlayerAgentMigrationPayload decode(AgentMigrationTask task) {
        if (PlayerAgentMigrationStatePacker.RUNTIME_STATE_TYPE.equals(task.snapshot().stateType())) {
            if (migrationCodec == null) {
                throw new IllegalStateException("player migration v2 codec is not configured");
            }
            return migrationCodec.decode(task.snapshot().stateBytes());
        }
        PlayerStateSnapshot state = serializer.deserialize(task.snapshot().stateBytes());
        return new PlayerAgentMigrationPayload(state, PlayerBusinessResponseSnapshot.empty(state.playerId()));
    }

    private static long parsePlayerId(AgentMigrationTask task) {
        try {
            return Long.parseLong(task.identity().key());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid player id: " + task.identity().key(), e);
        }
    }
}
