package com.commonbattle.example.cross;

/**
 * 玩家进入跨服 Scene 的业务失败分类。
 * 上层网关或客户端可依据分类决定提示、排队或延迟重试。
 */
public enum EnterSceneFailureCode {
    SCENE_BUSY,
    SCENE_TIMEOUT,
    RPC_REJECTED,
    SCENE_UNAVAILABLE,
    UNKNOWN
}
