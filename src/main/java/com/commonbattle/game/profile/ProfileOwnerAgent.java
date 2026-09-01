package com.commonbattle.game.profile;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.EventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 玩家基础资料 owner Agent。
 * 名字、外观、联盟摘要等展示数据在这里串行修改，并发布带完整 snapshot 的变更事件。
 */
public final class ProfileOwnerAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final EventPublisher publisher;
    private final Clock clock;
    private final long playerId;
    private String name;
    private int level;
    private AppearanceSummary appearance = AppearanceSummary.defaults();
    private AllianceBrief alliance = AllianceBrief.none();
    private FriendBrief friends = new FriendBrief(0, 0);
    private long revision;

    public ProfileOwnerAgent(
            AgentMessagePort messages,
            ActorRef self,
            EventPublisher publisher,
            Clock clock,
            long playerId,
            String name,
            int level
    ) {
        this.messages = messages;
        this.self = self;
        this.publisher = publisher;
        this.clock = clock;
        this.playerId = playerId;
        this.name = name;
        this.level = level;
    }

    public void rename(String newName) {
        messages.tellLocal(self, ignored -> {
            name = newName;
            publish(Set.of(ProfileField.NAME));
        });
    }

    public void changeAppearance(AppearanceSummary newAppearance) {
        messages.tellLocal(self, ignored -> {
            appearance = newAppearance;
            publish(Set.of(ProfileField.APPEARANCE));
        });
    }

    public void changeAlliance(AllianceBrief newAlliance) {
        messages.tellLocal(self, ignored -> {
            alliance = newAlliance;
            publish(Set.of(ProfileField.ALLIANCE));
        });
    }

    public void changeFriendBrief(FriendBrief newFriends) {
        messages.tellLocal(self, ignored -> {
            friends = newFriends;
            publish(Set.of(ProfileField.FRIENDS));
        });
    }

    public void levelUpTo(int newLevel) {
        messages.tellLocal(self, ignored -> {
            level = newLevel;
            publish(Set.of(ProfileField.LEVEL));
        });
    }

    public void snapshot(Consumer<PlayerProfileSnapshot> callback) {
        messages.tellLocal(self, ignored -> callback.accept(snapshot(clock.instant())));
    }

    private void publish(Set<ProfileField> changedFields) {
        // 玩家资料发布边界：owner 状态先完成修改，再递增 revision，最后发布完整快照供订阅方更新本地 cache。
        revision++;
        publisher.publish(new ProfileChangedEvent(playerId, changedFields, snapshot(clock.instant())));
    }

    private PlayerProfileSnapshot snapshot(Instant now) {
        return new PlayerProfileSnapshot(playerId, name, level, appearance, alliance, friends, revision, now);
    }
}
