package com.commonbattle.game.social;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.event.SnapshotEventPublisher;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 好友 owner Agent 管理器。
 * Game 服通过它按玩家懒加载好友 Actor，业务入口只提交命令，不直接修改好友集合。
 */
public final class FriendAgentManager {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final SnapshotEventPublisher<FriendSnapshot, FriendChangedEvent> publisher;
    private final Optional<AgentLifecycleManager> lifecycles;
    private final ConcurrentHashMap<Long, FriendAgent> agents = new ConcurrentHashMap<>();

    public FriendAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            FriendSnapshotRepository snapshots,
            EventPublisher events,
            FriendSummaryListener summaries
    ) {
        this(actors, messages, snapshots, events, summaries, null);
    }

    public FriendAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            FriendSnapshotRepository snapshots,
            EventPublisher events,
            FriendSummaryListener summaries,
            AgentLifecycleManager lifecycles
    ) {
        this(actors, messages, (snapshot, event) -> {
            snapshots.save(snapshot);
            summaries.onFriendSnapshot(snapshot);
            events.publish(event);
        }, lifecycles);
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(summaries, "summaries");
    }

    public FriendAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            SnapshotEventPublisher<FriendSnapshot, FriendChangedEvent> publisher,
            AgentLifecycleManager lifecycles
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.lifecycles = Optional.ofNullable(lifecycles);
    }

    public FriendAgent getOrCreate(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        return agents.computeIfAbsent(playerId, this::create);
    }

    public void add(long playerId, long friendId) {
        getOrCreate(playerId).add(friendId);
    }

    public void remove(long playerId, long friendId) {
        getOrCreate(playerId).remove(friendId);
    }

    public FriendAgentManagerStats stats() {
        return new FriendAgentManagerStats(agents.size());
    }

    private FriendAgent create(long playerId) {
        String actorId = "friend-" + playerId;
        return FriendAgent.withSnapshotPublisher(
                messages,
                lifecycles.map(manager -> manager.activate(AgentIdentity.friend(playerId), actorId).actorRef())
                        .orElseGet(() -> actors.actor(actorId)),
                playerId,
                publisher
        );
    }
}
