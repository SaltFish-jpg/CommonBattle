package com.commonbattle.game.chat;

import com.commonbattle.actor.agent.AgentIdentity;

import java.util.Optional;

/**
 * Chat Agent 快照仓储。实现必须对单 identity 提供跨进程原子 CAS。
 * 本接口是阻塞存储边界，只能由专用 I/O 执行器调用，不能在 Actor 或 Netty 线程直接调用。
 */
public interface ChatStateStore {
    Optional<VersionedChatState> load(AgentIdentity identity);

    ChatStateSaveResult save(
            AgentIdentity identity,
            long expectedVersion,
            long ownerToken,
            ChatChannelSnapshot snapshot
    );
}
