package com.commonbattle.game.profile;

import com.commonbattle.cluster.event.EventReplayRepairer;
import com.commonbattle.game.snapshot.SnapshotRepairer;

import java.util.Objects;
import java.util.Set;

/**
 * Profile 事件 replay 缺口修补器。
 * 当中心事件窗口不足时，它回 Profile owner 读取最新快照并刷新本地 cache。
 */
public final class ProfileEventReplayRepairer implements EventReplayRepairer {
    private final SnapshotRepairer repairer;

    public ProfileEventReplayRepairer(ProfileSnapshotReader reader, LocalProfileCache cache) {
        this(new ProfileSnapshotRepairer(reader, cache));
    }

    public ProfileEventReplayRepairer(SnapshotRepairer repairer) {
        this.repairer = Objects.requireNonNull(repairer, "repairer");
    }

    @Override
    public void repair(String topic, Set<String> ownerKeys) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(ownerKeys, "ownerKeys");
        if (!ProfileChangedEvent.TOPIC.equals(topic)) {
            throw new IllegalArgumentException("topic must be " + ProfileChangedEvent.TOPIC);
        }
        repairer.repair(ownerKeys);
    }
}
