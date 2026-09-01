package com.commonbattle.game.social;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.EventPublisher;

import java.util.HashSet;
import java.util.Set;
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
    private final Set<Long> members = new HashSet<>();
    private long revision;

    public AllianceAgent(AgentMessagePort messages, ActorRef self, long allianceId, EventPublisher publisher) {
        this.messages = messages;
        this.self = self;
        this.allianceId = allianceId;
        this.publisher = publisher;
    }

    public void join(long playerId) {
        messages.tellLocal(self, ignored -> {
            if (members.add(playerId)) {
                publish(playerId, AllianceMemberAction.JOIN);
            }
        });
    }

    public void leave(long playerId) {
        messages.tellLocal(self, ignored -> {
            if (members.remove(playerId)) {
                publish(playerId, AllianceMemberAction.LEAVE);
            }
        });
    }

    public void snapshot(Consumer<AllianceSnapshot> callback) {
        messages.tellLocal(self, ignored -> callback.accept(new AllianceSnapshot(allianceId, revision, members)));
    }

    private void publish(long playerId, AllianceMemberAction action) {
        // 联盟关系变更边界：状态修改成功后递增 revision，再发布事件，订阅方以 revision 判断顺序。
        revision++;
        publisher.publish(new AllianceMemberChangedEvent(allianceId, playerId, action, revision));
    }
}
