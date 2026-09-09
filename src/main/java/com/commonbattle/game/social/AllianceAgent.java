package com.commonbattle.game.social;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.EventPublisher;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 联盟关系 owner Agent。
 * 加入、退出和 revision 递增都在联盟 Actor 邮箱中串行执行，订阅事件只表达变化事实。
 */
public final class AllianceAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final long allianceId;
    private final EventPublisher publisher;
    private final AllianceSnapshotRepository snapshots;
    private final Set<Long> members = new HashSet<>();
    private long revision;

    public AllianceAgent(AgentMessagePort messages, ActorRef self, long allianceId, EventPublisher publisher) {
        this(messages, self, allianceId, publisher, NoopAllianceSnapshotRepository.INSTANCE);
    }

    public AllianceAgent(
            AgentMessagePort messages,
            ActorRef self,
            long allianceId,
            EventPublisher publisher,
            AllianceSnapshotRepository snapshots
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.allianceId = allianceId;
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public void join(long playerId) {
        messages.tellLocal(self, ignored -> joinNow(playerId));
    }

    public void leave(long playerId) {
        messages.tellLocal(self, ignored -> leaveNow(playerId));
    }

    public void snapshot(Consumer<AllianceSnapshot> callback) {
        messages.tellLocal(self, ignored -> callback.accept(snapshotNow()));
    }

    public AllianceSnapshot joinNow(long playerId) {
        validatePlayer(playerId);
        if (members.add(playerId)) {
            publish(playerId, AllianceMemberAction.JOIN);
        }
        return snapshotNow();
    }

    public AllianceSnapshot leaveNow(long playerId) {
        validatePlayer(playerId);
        if (members.remove(playerId)) {
            publish(playerId, AllianceMemberAction.LEAVE);
        }
        return snapshotNow();
    }

    public AllianceSnapshot snapshotNow() {
        return new AllianceSnapshot(allianceId, revision, members);
    }

    private void publish(long playerId, AllianceMemberAction action) {
        // 联盟关系变更边界：状态修改成功后递增 revision，先写快照，再发布事件，订阅方以 revision 判断顺序。
        revision++;
        snapshots.save(snapshotNow());
        publisher.publish(new AllianceMemberChangedEvent(allianceId, playerId, action, revision));
    }

    private void validatePlayer(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
    }

    private enum NoopAllianceSnapshotRepository implements AllianceSnapshotRepository {
        INSTANCE;

        @Override
        public void save(AllianceSnapshot snapshot) {
        }

        @Override
        public Optional<AllianceSnapshot> find(long allianceId) {
            return Optional.empty();
        }
    }
}
