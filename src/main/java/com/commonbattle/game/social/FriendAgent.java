package com.commonbattle.game.social;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.event.SnapshotEventPublisher;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 玩家好友关系 owner Agent。
 * 一个玩家一份好友列表，增删好友和 revision 递增都在该玩家好友 Actor 邮箱中串行执行。
 */
public final class FriendAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final long playerId;
    private final SnapshotEventPublisher<FriendSnapshot, FriendChangedEvent> publisher;
    private final Set<Long> friends = new HashSet<>();
    private long revision;

    public FriendAgent(AgentMessagePort messages, ActorRef self, long playerId, EventPublisher publisher) {
        this(messages, self, playerId, publisher, NoopFriendSnapshotRepository.INSTANCE);
    }

    public FriendAgent(
            AgentMessagePort messages,
            ActorRef self,
            long playerId,
            EventPublisher publisher,
            FriendSnapshotRepository snapshots
    ) {
        this(messages, self, playerId, publisher, snapshots, FriendSummaryListener.noop());
    }

    public FriendAgent(
            AgentMessagePort messages,
            ActorRef self,
            long playerId,
            EventPublisher publisher,
            FriendSnapshotRepository snapshots,
            FriendSummaryListener summaryListener
    ) {
        this(messages, self, playerId, (snapshot, event) -> {
            snapshots.save(snapshot);
            summaryListener.onFriendSnapshot(snapshot);
            publisher.publish(event);
        });
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(summaryListener, "summaryListener");
    }

    private FriendAgent(
            AgentMessagePort messages,
            ActorRef self,
            long playerId,
            SnapshotEventPublisher<FriendSnapshot, FriendChangedEvent> publisher
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        this.playerId = playerId;
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public static FriendAgent withSnapshotPublisher(
            AgentMessagePort messages,
            ActorRef self,
            long playerId,
            SnapshotEventPublisher<FriendSnapshot, FriendChangedEvent> publisher
    ) {
        return new FriendAgent(messages, self, playerId, publisher);
    }

    public void add(long friendId) {
        messages.tellLocal(self, ignored -> addNow(friendId));
    }

    public void remove(long friendId) {
        messages.tellLocal(self, ignored -> removeNow(friendId));
    }

    public void snapshot(Consumer<FriendSnapshot> callback) {
        messages.tellLocal(self, ignored -> callback.accept(snapshotNow()));
    }

    public FriendSnapshot addNow(long friendId) {
        validateFriend(friendId);
        if (friends.add(friendId)) {
            publish(friendId, FriendRelationAction.ADD);
        }
        return snapshotNow();
    }

    public FriendSnapshot removeNow(long friendId) {
        validateFriend(friendId);
        if (friends.remove(friendId)) {
            publish(friendId, FriendRelationAction.REMOVE);
        }
        return snapshotNow();
    }

    private void publish(long friendId, FriendRelationAction action) {
        // 好友关系变更边界：owner 状态修改后递增 revision，先写关系快照和 Profile 摘要，再发布关系事件。
        revision++;
        FriendSnapshot snapshot = snapshotNow();
        publisher.publish(snapshot, new FriendChangedEvent(playerId, friendId, action, revision));
    }

    public FriendSnapshot snapshotNow() {
        return new FriendSnapshot(playerId, revision, friends);
    }

    private void validateFriend(long friendId) {
        if (friendId <= 0) {
            throw new IllegalArgumentException("friendId must be positive");
        }
        if (friendId == playerId) {
            throw new IllegalArgumentException("friendId must not be self");
        }
    }

    private enum NoopFriendSnapshotRepository implements FriendSnapshotRepository {
        INSTANCE;

        @Override
        public void save(FriendSnapshot snapshot) {
        }

        @Override
        public Optional<FriendSnapshot> find(long playerId) {
            return Optional.empty();
        }
    }
}
