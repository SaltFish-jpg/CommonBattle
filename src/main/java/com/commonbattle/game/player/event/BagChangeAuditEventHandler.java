package com.commonbattle.game.player.event;

import com.commonbattle.game.bag.BagChangeAuditRecord;
import com.commonbattle.game.bag.BagChangeAuditSink;

import java.util.Map;
import java.util.Objects;

/**
 * 将 bag.changed 玩家领域事件展开为背包变更流水。
 * 应挂在 PlayerDomainEventProcessor 上，在 owner revision 去重之后执行，避免重复事件写入重复流水。
 */
public final class BagChangeAuditEventHandler implements PlayerDomainEventHandler {
    private final BagChangeAuditSink sink;

    public BagChangeAuditEventHandler(BagChangeAuditSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void handle(PlayerDomainEventDelivery delivery) {
        PlayerDomainVersionedEvent event = Objects.requireNonNull(delivery, "delivery").event();
        if (!BagChangedEvent.TYPE.equals(event.eventType())) {
            return;
        }
        Map<String, String> attributes = event.attributes();
        String reason = attributes.getOrDefault("reason", event.subject());
        int changeCount = parseInt(attributes.get("changeCount"), 0);
        for (int index = 0; index < changeCount; index++) {
            String itemId = attributes.get("change." + index + ".itemId");
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            sink.record(new BagChangeAuditRecord(
                    event.playerId(),
                    event.revision(),
                    reason,
                    itemId,
                    parseInt(attributes.get("change." + index + ".before"), 0),
                    parseInt(attributes.get("change." + index + ".after"), 0),
                    event.occurredAt()
            ));
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
