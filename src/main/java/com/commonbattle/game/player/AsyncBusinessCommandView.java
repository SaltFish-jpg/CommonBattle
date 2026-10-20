package com.commonbattle.game.player;

/**
 * 玩家异步业务命令只读观测视图。
 * Game 服注册后，健康探针可以统一观察跨服 RPC、缓存、风控等回调链路。
 */
public interface AsyncBusinessCommandView {
    AsyncBusinessCommandStats asyncBusinessCommandStats();
}
