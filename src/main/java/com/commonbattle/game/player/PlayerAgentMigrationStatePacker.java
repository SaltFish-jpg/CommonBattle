package com.commonbattle.game.player;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.migration.AgentMigrationSnapshot;
import com.commonbattle.actor.agent.migration.AgentMigrationStatePacker;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.session.PlayerCommandSequencer;

import java.util.Objects;

/**
 * 玩家 Agent 迁移快照打包器。
 * 打包动作由迁移协调器投递到源玩家邮箱内执行，因此可以直接读取玩家聚合并得到一致快照。
 */
public final class PlayerAgentMigrationStatePacker implements AgentMigrationStatePacker {
    public static final String STATE_TYPE = "commonbattle.player.state.v1";
    public static final String RUNTIME_STATE_TYPE = "commonbattle.player.runtime-state.v2";

    private final PlayerGameAgentManager agents;
    private final PlayerStateSnapshotSerializer serializer;
    private final PlayerCommandSequencer commandSequencer;
    private final PlayerBusinessResponseHub businessResponses;
    private final PlayerAgentMigrationPayloadCodec migrationCodec;

    public PlayerAgentMigrationStatePacker(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer
    ) {
        this(agents, serializer, null);
    }

    public PlayerAgentMigrationStatePacker(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer,
            PlayerCommandSequencer commandSequencer
    ) {
        this(agents, serializer, commandSequencer, null, null);
    }

    public PlayerAgentMigrationStatePacker(
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
    public AgentMigrationSnapshot pack(AgentIdentity identity, AgentLocation target, ActorContext context) {
        if (!AgentIdentity.PLAYER.equals(identity.type())) {
            throw new IllegalArgumentException("unsupported migration identity: " + identity.type());
        }
        long playerId = parsePlayerId(identity);
        PlayerGameAgent agent = agents.get(playerId)
                .orElseThrow(() -> new IllegalStateException("player agent not loaded: " + playerId));
        PlayerStateSnapshot snapshot = agent.exportForMigrationInCurrentMailbox();
        if (commandSequencer != null) {
            snapshot = snapshot.withCommandSequencer(commandSequencer.snapshotForPlayer(playerId));
        }
        if (businessResponses != null) {
            int pending = businessResponses.pendingResponses(playerId);
            if (pending > 0) {
                throw new PendingPlayerBusinessResponseException(playerId, pending);
            }
            PlayerAgentMigrationPayload payload = new PlayerAgentMigrationPayload(
                    snapshot,
                    businessResponses.snapshotForPlayer(playerId)
            );
            return new AgentMigrationSnapshot(RUNTIME_STATE_TYPE, migrationCodec.encode(payload));
        }
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
