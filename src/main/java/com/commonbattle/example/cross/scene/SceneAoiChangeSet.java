package com.commonbattle.example.cross.scene;

import java.util.List;

/**
 * 单次场景位置变化产生的 AOI 差量。
 * visibleEnter/visibleLeave 是移动者视角；observerEnter/observerLeave 是其他玩家对移动者的视角。
 */
public record SceneAoiChangeSet(
        long playerId,
        List<Long> visibleEnter,
        List<Long> visibleLeave,
        List<Long> observerEnter,
        List<Long> observerLeave
) {
    public SceneAoiChangeSet {
        visibleEnter = List.copyOf(visibleEnter);
        visibleLeave = List.copyOf(visibleLeave);
        observerEnter = List.copyOf(observerEnter);
        observerLeave = List.copyOf(observerLeave);
    }

    public static SceneAoiChangeSet empty(long playerId) {
        return new SceneAoiChangeSet(playerId, List.of(), List.of(), List.of(), List.of());
    }

    public SceneAoiChangeSet plus(SceneAoiChangeSet other) {
        if (playerId != other.playerId) {
            throw new IllegalArgumentException("playerId mismatch");
        }
        return new SceneAoiChangeSet(
                playerId,
                concat(visibleEnter, other.visibleEnter),
                concat(visibleLeave, other.visibleLeave),
                concat(observerEnter, other.observerEnter),
                concat(observerLeave, other.observerLeave)
        );
    }

    private static List<Long> concat(List<Long> first, List<Long> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        java.util.ArrayList<Long> values = new java.util.ArrayList<>(first.size() + second.size());
        values.addAll(first);
        values.addAll(second);
        return values;
    }
}
