package com.commonbattle.game.bag;

/**
 * 单次背包变更项。
 */
public record BagChange(String itemId, int before, int after) {
}
