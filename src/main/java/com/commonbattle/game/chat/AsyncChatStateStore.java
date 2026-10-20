package com.commonbattle.game.chat;

import com.commonbattle.actor.agent.AgentIdentity;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 非阻塞 Chat 状态仓储。完成回调运行在 I/O 线程，调用方只能把结果重新投递到 Agent mailbox。
 */
public interface AsyncChatStateStore {
    void load(
            AgentIdentity identity,
            Consumer<Optional<VersionedChatState>> success,
            Consumer<Throwable> failure
    );

    void save(
            AgentIdentity identity,
            long expectedVersion,
            long ownerToken,
            ChatChannelSnapshot snapshot,
            Consumer<ChatStateSaveResult> success,
            Consumer<Throwable> failure
    );
}
