package com.commonbattle.actor;

/**
 * Actor 邮箱任务类别。
 * 用于给玩家输入、RPC 回包、定时器和观测类任务配置不同背压阈值，避免单类消息挤爆整个邮箱。
 */
public enum ActorTaskCategory {
    DEFAULT,
    PLAYER_COMMAND,
    EVENT,
    RPC_CALLBACK,
    TIMER,
    OBSERVABILITY,
    SYSTEM
}
