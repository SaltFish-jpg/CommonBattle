package com.commonbattle.game.profile;

import com.commonbattle.game.event.VersionedEvent;

import java.util.Set;

/**
 * 玩家基础资料变更事件。
 * 事件携带完整 snapshot，订阅服务可直接刷新本地 cache，Redis/远程缓存只作为补偿读取入口。
 */
public record ProfileChangedEvent(
        long playerId,
        Set<ProfileField> changedFields,
        PlayerProfileSnapshot snapshot
) implements VersionedEvent {
    public static final String TOPIC = "profile.changed";

    public ProfileChangedEvent() {
        this(1, Set.of(), new PlayerProfileSnapshot());
    }

    public ProfileChangedEvent {
        changedFields = Set.copyOf(changedFields);
        if (playerId != snapshot.playerId()) {
            throw new IllegalArgumentException("event playerId must match snapshot");
        }
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String ownerKey() {
        return ownerKey(playerId);
    }

    @Override
    public long revision() {
        return snapshot.revision();
    }

    public static String ownerKey(long playerId) {
        return "profile:" + playerId;
    }
}
