package com.commonbattle.game.player;

/**
 * 玩家 Agent 失去持久化 owner 身份时的隔离回调。
 * 管理器应在当前邮箱任务结束前撤销路由、定时器和本地索引。
 */
@FunctionalInterface
public interface PlayerAgentFenceListener {
    PlayerAgentFenceListener NOOP = (agent, conflict) -> {
    };

    void fenced(PlayerGameAgent agent, PlayerMutationConflictException conflict);
}
