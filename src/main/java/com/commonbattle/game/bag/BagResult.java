package com.commonbattle.game.bag;

import java.util.List;

/**
 * 背包操作结果。
 */
public record BagResult(List<BagChange> changes) {
    public BagResult {
        changes = List.copyOf(changes);
    }
}
