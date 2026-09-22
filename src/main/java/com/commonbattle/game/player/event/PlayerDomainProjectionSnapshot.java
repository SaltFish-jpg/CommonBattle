package com.commonbattle.game.player.event;

import com.commonbattle.game.player.PlayerStateSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家领域事件可快照化投影。
 * 它只承载能从玩家状态确定性重建的派生视图，事件流缺口时用于刷新 Scene/Chat 本地投影。
 */
public record PlayerDomainProjectionSnapshot(
        long playerId,
        long eventRevision,
        Map<String, Integer> stageClearCounts
) {
    public PlayerDomainProjectionSnapshot {
        stageClearCounts = Map.copyOf(stageClearCounts);
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (eventRevision < 0) {
            throw new IllegalArgumentException("eventRevision must not be negative");
        }
        stageClearCounts.forEach((stageId, clearCount) -> {
            Objects.requireNonNull(stageId, "stageId");
            if (stageId.isBlank()) {
                throw new IllegalArgumentException("stageId must not be blank");
            }
            if (clearCount == null || clearCount < 0) {
                throw new IllegalArgumentException("clearCount must not be negative");
            }
        });
    }

    public static PlayerDomainProjectionSnapshot from(PlayerStateSnapshot state) {
        Objects.requireNonNull(state, "state");
        Map<String, Integer> clears = new LinkedHashMap<>();
        state.battle().stages().forEach((stageId, progress) ->
                clears.put(stageId, progress.clearCount()));
        return new PlayerDomainProjectionSnapshot(state.playerId(), state.eventRevision(), clears);
    }

    public String ownerKey() {
        return PlayerDomainVersionedEvent.ownerKey(playerId);
    }

    public int totalStageClears() {
        return stageClearCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
