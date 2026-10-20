package com.commonbattle.game.chat;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 异步聊天消息策略。
 * 适合需要跨服读取玩家资料、风控或禁言状态的发言链路；回调方只应把结果投递回频道 Actor。
 */
public interface AsyncChatMessagePolicy {
    void inspect(ChatSendRequest request, Consumer<ChatMessageDecision> callback);

    static AsyncChatMessagePolicy direct(ChatMessagePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return (request, callback) -> callback.accept(policy.inspect(request));
    }
}
