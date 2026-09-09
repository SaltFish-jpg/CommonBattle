package com.commonbattle.game.player;

/**
 * 可被运维健康探针读取的玩家业务响应等待视图。
 */
@FunctionalInterface
public interface PlayerBusinessResponseView {
    PlayerBusinessResponseStats stats();
}
