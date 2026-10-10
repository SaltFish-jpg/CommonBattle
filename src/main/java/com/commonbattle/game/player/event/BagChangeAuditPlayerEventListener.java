package com.commonbattle.game.player.event;

import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagChangeAuditRecord;
import com.commonbattle.game.bag.BagChangeAuditSink;
import com.commonbattle.game.player.PlayerDomainEventListener;
import com.commonbattle.game.player.PlayerProfile;

import java.time.Clock;
import java.util.Objects;

/**
 * 在玩家邮箱内把 bag.changed 事件写成本地背包流水。
 * 该监听器不做远程调用，只记录已经完成结算的结果，适合挂在 Game 服 source-side 审计链路。
 */
public final class BagChangeAuditPlayerEventListener implements PlayerDomainEventListener {
    private final BagChangeAuditSink sink;
    private final Clock clock;

    public BagChangeAuditPlayerEventListener(BagChangeAuditSink sink, Clock clock) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onEvent(PlayerProfile profile, long eventRevision, PlayerDomainEvent event) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(event, "event");
        if (!(event instanceof BagChangedEvent bagChanged)) {
            return;
        }
        for (BagChange change : bagChanged.changes()) {
            sink.record(new BagChangeAuditRecord(
                    profile.playerId(),
                    eventRevision,
                    bagChanged.reason(),
                    change.itemId(),
                    change.before(),
                    change.after(),
                    clock.instant()
            ));
        }
    }
}
