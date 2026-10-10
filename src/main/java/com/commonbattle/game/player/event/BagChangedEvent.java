package com.commonbattle.game.player.event;

import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 玩家背包发生实际变更后产生的业务事件。
 * 背包事件用于审计、跨服感知和本地投影刷新；发布方应在玩家 mailbox 内完成背包结算后再发布。
 */
public record BagChangedEvent(long playerId, String reason, List<BagChange> changes, boolean replayed)
        implements PlayerDomainEvent {
    public static final String TYPE = "bag.changed";

    public BagChangedEvent {
        reason = Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        changes = List.copyOf(changes);
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("changes must not be empty");
        }
    }

    public static Optional<BagChangedEvent> from(
            long playerId,
            String reason,
            boolean replayed,
            BagResult... results
    ) {
        Objects.requireNonNull(results, "results");
        List<BagChange> changes = new ArrayList<>();
        for (BagResult result : results) {
            if (result != null) {
                changes.addAll(result.changes());
            }
        }
        if (changes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BagChangedEvent(playerId, reason, changes, replayed));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String subject() {
        return reason;
    }

    @Override
    public int delta() {
        return changes.stream()
                .mapToInt(change -> Math.abs(change.after() - change.before()))
                .sum();
    }

    @Override
    public Map<String, String> attributes() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("reason", reason);
        attributes.put("changeCount", Integer.toString(changes.size()));
        for (int i = 0; i < changes.size(); i++) {
            BagChange change = changes.get(i);
            attributes.put("change." + i + ".itemId", change.itemId());
            attributes.put("change." + i + ".before", Integer.toString(change.before()));
            attributes.put("change." + i + ".after", Integer.toString(change.after()));
        }
        return attributes;
    }
}
