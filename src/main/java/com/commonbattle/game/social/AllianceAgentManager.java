package com.commonbattle.game.social;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.EventPublisher;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联盟 owner Agent 管理器。
 * 每个联盟一个 Actor owner，成员变更、revision 递增、快照写入和事件发布都在联盟 mailbox 内串行完成。
 */
public final class AllianceAgentManager {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final AllianceSnapshotRepository snapshots;
    private final EventPublisher events;
    private final Optional<AgentLifecycleManager> lifecycles;
    private final ConcurrentHashMap<Long, AllianceAgent> agents = new ConcurrentHashMap<>();

    public AllianceAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            AllianceSnapshotRepository snapshots,
            EventPublisher events
    ) {
        this(actors, messages, snapshots, events, null);
    }

    public AllianceAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            AllianceSnapshotRepository snapshots,
            EventPublisher events,
            AgentLifecycleManager lifecycles
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.events = Objects.requireNonNull(events, "events");
        this.lifecycles = Optional.ofNullable(lifecycles);
    }

    public AllianceAgent getOrCreate(long allianceId) {
        if (allianceId <= 0) {
            throw new IllegalArgumentException("allianceId must be positive");
        }
        return agents.computeIfAbsent(allianceId, this::create);
    }

    public void join(long allianceId, long playerId) {
        getOrCreate(allianceId).join(playerId);
    }

    public void leave(long allianceId, long playerId) {
        getOrCreate(allianceId).leave(playerId);
    }

    public int activeAgents() {
        return agents.size();
    }

    private AllianceAgent create(long allianceId) {
        String actorId = "alliance-" + allianceId;
        return new AllianceAgent(
                messages,
                lifecycles.map(manager -> manager.activate(AgentIdentity.alliance(allianceId), actorId).actorRef())
                        .orElseGet(() -> actors.actor(actorId)),
                allianceId,
                events,
                snapshots
        );
    }
}
