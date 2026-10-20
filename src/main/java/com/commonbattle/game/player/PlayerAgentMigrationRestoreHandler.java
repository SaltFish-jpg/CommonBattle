package com.commonbattle.game.player;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.migration.AgentMigrationAcceptRequest;
import com.commonbattle.actor.agent.migration.AgentMigrationRestoreHandler;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.session.PlayerCommandSequencer;

import java.util.Objects;

/**
 * 玩家 Agent 迁入恢复器。
 * 该方法由目标 Actor 邮箱执行，恢复后的玩家业务仍保持单邮箱串行语义。
 */
public final class PlayerAgentMigrationRestoreHandler implements AgentMigrationRestoreHandler {
    private final PlayerGameAgentManager agents;
    private final PlayerStateSnapshotSerializer serializer;
    private final PlayerCommandSequencer commandSequencer;
    private final PlayerBusinessResponseHub businessResponses;
    private final PlayerAgentMigrationPayloadCodec migrationCodec;

    public PlayerAgentMigrationRestoreHandler(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer
    ) {
        this(agents, serializer, null);
    }

    public PlayerAgentMigrationRestoreHandler(
            PlayerGameAgentManager agents,
            PlayerStateSnapshotSerializer serializer,
            PlayerCommandSequencer commandSequencer
    ) {
        this(agents, serializer, commandSequencer, null, null);
    }

    public PlayerAgentMigrationRestoreHandler(
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
    public void restore(AgentMigrationAcceptRequest request, ActorContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        if (!AgentIdentity.PLAYER.equals(request.identity().type())) {
            throw new IllegalArgumentException("unsupported migration identity: " + request.identity().type());
        }
        if (!PlayerAgentMigrationStatePacker.STATE_TYPE.equals(request.stateType())
                && !PlayerAgentMigrationStatePacker.RUNTIME_STATE_TYPE.equals(request.stateType())) {
            throw new IllegalArgumentException("unsupported player migration state type: " + request.stateType());
        }
        PlayerAgentMigrationPayload payload = decode(request);
        PlayerStateSnapshot snapshot = payload.state();
        long expectedPlayerId = parsePlayerId(request.identity());
        if (snapshot.playerId() != expectedPlayerId) {
            throw new IllegalArgumentException("player migration snapshot identity mismatch");
        }
        PlayerGameAgentHandle restored = agents.restoreMigrated(snapshot, context.self());
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

    private PlayerAgentMigrationPayload decode(AgentMigrationAcceptRequest request) {
        if (PlayerAgentMigrationStatePacker.RUNTIME_STATE_TYPE.equals(request.stateType())) {
            if (migrationCodec == null) {
                throw new IllegalStateException("player migration v2 codec is not configured");
            }
            return migrationCodec.decode(request.stateBytes());
        }
        PlayerStateSnapshot state = serializer.deserialize(request.stateBytes());
        return new PlayerAgentMigrationPayload(state, PlayerBusinessResponseSnapshot.empty(state.playerId()));
    }

    private static long parsePlayerId(AgentIdentity identity) {
        try {
            return Long.parseLong(identity.key());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid player id: " + identity.key(), e);
        }
    }
}
